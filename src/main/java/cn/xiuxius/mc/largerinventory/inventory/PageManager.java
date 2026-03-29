package cn.xiuxius.mc.largerinventory.inventory;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.config.PluginConfig;
import cn.xiuxius.mc.largerinventory.database.PageItemDAO;
import cn.xiuxius.mc.largerinventory.database.PlayerMetaDAO;
import cn.xiuxius.mc.largerinventory.database.cache.PageCache;
import cn.xiuxius.mc.largerinventory.database.model.PlayerMeta;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分页管理器（内存优先 + 写回缓存模型）
 *
 * <ul>
 *   <li>每个玩家维护一个 {@link PageCache}（LRU，容量 PAGE_CACHE_SIZE 页）</li>
 *   <li>翻页操作在内存中即时完成；脏页由定时器批量异步写入 DB</li>
 *   <li>玩家退出时同步 flush，确保不丢数据</li>
 *   <li>跨页操作仅查缓存；缺页时通过 preloadPagesForPickup 提前异步加载</li>
 * </ul>
 */
public class PageManager {

    public static final int MAX_PAGES_HARD_LIMIT = 99;
    private static final int PAGE_CACHE_SIZE = 6;

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final ButtonManager buttonManager;
    private final MessageManager messageManager;
    private final PlayerMetaDAO metaDao;
    private final PageItemDAO pageItemDao;

    private final Map<UUID, PlayerPageData> playerDataCache = new ConcurrentHashMap<>();

    // 按钮点击防重与冷却
    private final Map<UUID, Integer> tickPacketCount = new HashMap<>();
    private final Map<UUID, Integer> pendingPageTurnTasks = new HashMap<>();
    private final Set<UUID> pageTurnCooldown = new HashSet<>();

    public PageManager(JavaPlugin plugin, ConfigManager configManager,
                       ButtonManager buttonManager, MessageManager messageManager,
                       PlayerMetaDAO metaDao, PageItemDAO pageItemDao) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.buttonManager = buttonManager;
        this.messageManager = messageManager;
        this.metaDao = metaDao;
        this.pageItemDao = pageItemDao;
    }

    /**
     * 玩家运行时状态（仅在主线程读写）
     */
    public static class PlayerPageData {
        private volatile int currentPage;
        private volatile int maxPage;
        private volatile boolean switching;
        final PageCache cache;

        PlayerPageData(int currentPage, int maxPage, PageCache cache) {
            this.currentPage = currentPage;
            this.maxPage = maxPage;
            this.cache = cache;
        }

        public int getCurrentPage() {
            return currentPage;
        }

        public int getMaxPage() {
            return maxPage;
        }

        public boolean isSwitching() {
            return switching;
        }
    }

    /**
     * 异步写入任务的快照描述
     */
    private record WriteTask(UUID uuid, int pageNum, Map<Integer, ItemStack> items,
                             int version, PageCache cache) {
    }


    public void initPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        try {
            metaDao.upsert(uuid, player.getName());
            PlayerMeta meta = metaDao.getByUUID(uuid);

            int currentPage = meta != null ? meta.getCurrentPage() : 0;
            int maxPage = meta != null ? meta.getMaxPage() : 0;

            PlayerPageData data = createPlayerData(uuid, currentPage, maxPage);
            playerDataCache.put(uuid, data);

            Map<Integer, ItemStack> items = pageItemDao.loadPage(uuid, currentPage);
            data.cache.put(currentPage, items);
            loadItemsToInventory(player, items);
            updateButtons(player, currentPage, maxPage);

            plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.PLAYER_DATA_LOADED,
                    "player", player.getName(),
                    "current", currentPage + 1,
                    "max", maxPage + 1));
        } catch (SQLException e) {
            plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.PLAYER_DATA_LOAD_FAILED,
                    "player", player.getName(), "error", e.getMessage()));
            PlayerPageData data = createPlayerData(uuid, 0, 0);
            playerDataCache.put(uuid, data);
            updateButtons(player, 0, 0);
        }
    }

    public void saveAndClearPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) return;

        snapshotToCache(player, data);
        flushDirtyPagesSync(uuid, data);
        playerDataCache.remove(uuid);
    }


    public void prevPage(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        if (data != null && data.currentPage > 0) {
            switchToPage(player, data.currentPage - 1);
        }
    }

    public void nextPage(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        if (data != null && data.currentPage < getEffectiveMaxPages() - 1) {
            switchToPage(player, data.currentPage + 1);
        }
    }

    public boolean canPrevPage(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        return data != null && !data.switching && data.currentPage > 0;
    }

    public boolean canNextPage(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        return data != null && !data.switching && data.currentPage < getEffectiveMaxPages() - 1;
    }

    public void switchToPage(Player player, int targetPage) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null || data.switching) return;
        if (targetPage < 0 || targetPage >= getEffectiveMaxPages()) return;

        data.switching = true;
        snapshotToCache(player, data);

        int prevPage = data.currentPage;
        data.currentPage = targetPage;

        clearInventoryMain(player);
        updateButtons(player, targetPage, data.maxPage);

        Map<Integer, ItemStack> cached = data.cache.get(targetPage);
        if (cached != null) {
            loadItemsToInventory(player, cached);
            updateButtons(player, targetPage, data.maxPage);
            data.switching = false;
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<Integer, ItemStack> items = pageItemDao.loadPage(uuid, targetPage);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    data.switching = false;
                    if (!player.isOnline()) return;
                    data.cache.put(targetPage, items);
                    loadItemsToInventory(player, items);
                    updateButtons(player, targetPage, data.maxPage);
                });
            } catch (SQLException e) {
                plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.PAGE_LOAD_FAILED,
                        "uuid", uuid, "page", targetPage + 1, "error", e.getMessage()));
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    data.switching = false;
                    data.currentPage = prevPage;
                    updateButtons(player, prevPage, data.maxPage);
                });
            }
        });
    }

    /**
     * 按钮点击入口（含多包防重 + 翻页冷却）
     * 仅在主线程调用。
     */
    public void onButtonClick(Player player, String buttonType) {
        UUID uuid = player.getUniqueId();
        int count = tickPacketCount.getOrDefault(uuid, 0) + 1;
        tickPacketCount.put(uuid, count);

        if (count > 1) {
            // 同一 tick 多次点击：判定为批量操作，取消挂起任务
            Integer pendingId = pendingPageTurnTasks.remove(uuid);
            if (pendingId != null) Bukkit.getScheduler().cancelTask(pendingId);
            Bukkit.getScheduler().runTaskLater(plugin, () -> tickPacketCount.remove(uuid), 3L);
            return;
        }

        int taskId = Bukkit.getScheduler().runTask(plugin, () -> {
            pendingPageTurnTasks.remove(uuid);
            tickPacketCount.remove(uuid);
            if (!player.isOnline()) return;
            if (pageTurnCooldown.contains(uuid)) return;
            pageTurnCooldown.add(uuid);
            Bukkit.getScheduler().runTaskLater(plugin, () -> pageTurnCooldown.remove(uuid), 2L);
            if (ButtonManager.BUTTON_PREV.equals(buttonType) && canPrevPage(player)) {
                prevPage(player);
            } else if (ButtonManager.BUTTON_NEXT.equals(buttonType) && canNextPage(player)) {
                nextPage(player);
            }
            player.updateInventory();
        }).getTaskId();
        pendingPageTurnTasks.put(uuid, taskId);
    }

    // 持久化

    /**
     * 定时刷脏入口
     *
     * <ol>
     *   <li>快照所有在线玩家的当前页（主线程）</li>
     *   <li>收集脏页快照（含版本号）</li>
     *   <li>异步批量写 DB</li>
     *   <li>写入成功后回到主线程调用 markClean，版本未变才清 dirty</li>
     * </ol>
     */
    public void flushAllDirtyPages() {
        // Phase 1: 快照当前页
        for (Map.Entry<UUID, PlayerPageData> entry : playerDataCache.entrySet()) {
            PlayerPageData data = entry.getValue();
            if (data.switching) continue;
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                snapshotToCache(player, data);
            }
        }

        // Phase 2: 收集脏页快照
        List<WriteTask> tasks = new ArrayList<>();
        Map<UUID, int[]> metas = new LinkedHashMap<>(); // uuid -> [currentPage, maxPage]

        for (Map.Entry<UUID, PlayerPageData> entry : playerDataCache.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerPageData data = entry.getValue();
            List<PageCache.DirtySnapshot> dirty = data.cache.collectDirty();
            if (!dirty.isEmpty()) {
                for (PageCache.DirtySnapshot s : dirty) {
                    tasks.add(new WriteTask(uuid, s.page(), s.items(), s.version(), data.cache));
                }
                metas.put(uuid, new int[]{data.currentPage, data.maxPage});
            }
        }

        if (tasks.isEmpty()) return;

        // Phase 3: 异步写 DB
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<WriteTask> succeeded = new ArrayList<>();
            for (WriteTask task : tasks) {
                try {
                    pageItemDao.savePage(task.uuid(), task.pageNum(), task.items());
                    succeeded.add(task);
                } catch (SQLException e) {
                    plugin.getLogger().warning(messageManager.getLog(MessageKeys.Log.PAGE_WRITE_FAILED,
                            "page", task.pageNum() + 1, "error", e.getMessage()));
                }
            }

            Set<UUID> metaDone = new HashSet<>();
            for (WriteTask task : succeeded) {
                if (metaDone.add(task.uuid())) {
                    int[] meta = metas.get(task.uuid());
                    try {
                        metaDao.update(task.uuid(), meta[0], meta[1]);
                    } catch (SQLException e) {
                        plugin.getLogger().warning(messageManager.getLog(
                                MessageKeys.Log.PAGE_METADATA_UPDATE_FAILED, "error", e.getMessage()));
                    }
                }
            }

            // Phase 4: 回到主线程 markClean（LRU 非线程安全）
            if (!succeeded.isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (WriteTask task : succeeded) {
                        task.cache().markClean(task.pageNum(), task.version());
                    }
                });
            }
        });
    }

    /**
     * 供 AdminCommand 强制 flush 某玩家
     */
    public void forceFlushPlayer(Player player) throws SQLException {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) return;
        Map<Integer, ItemStack> items = snapshotCurrentPage(player);
        pageItemDao.savePage(uuid, data.currentPage, items);
    }

    public void saveCurrentPage(Player player) throws SQLException {
        forceFlushPlayer(player);
    }

    private void flushDirtyPagesSync(UUID uuid, PlayerPageData data) {
        try {
            List<PageCache.DirtySnapshot> dirty = data.cache.collectDirty();
            for (PageCache.DirtySnapshot s : dirty) {
                pageItemDao.savePage(uuid, s.page(), s.items());
                data.cache.markClean(s.page(), s.version());
            }
            metaDao.update(uuid, data.currentPage, data.maxPage);
        } catch (SQLException e) {
            plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.PLAYER_DATA_SAVE_FAILED,
                    "uuid", uuid, "error", e.getMessage()));
        }
    }

    // UI
    public void reloadCurrentPage(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null || data.switching) return;

        clearInventoryMain(player);
        Map<Integer, ItemStack> cached = data.cache.get(data.currentPage);
        if (cached != null) {
            loadItemsToInventory(player, cached);
            updateButtons(player, data.currentPage, data.maxPage);
        } else {
            data.switching = true;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Map<Integer, ItemStack> items = pageItemDao.loadPage(uuid, data.currentPage);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        data.switching = false;
                        if (!player.isOnline()) return;
                        data.cache.put(data.currentPage, items);
                        loadItemsToInventory(player, items);
                        updateButtons(player, data.currentPage, data.maxPage);
                    });
                } catch (SQLException e) {
                    plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.PAGE_RELOAD_FAILED,
                            "uuid", uuid, "error", e.getMessage()));
                    plugin.getServer().getScheduler().runTask(plugin, () -> data.switching = false);
                }
            });
        }
    }

    public void restoreButtons(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        if (data == null) return;
        updateButtons(player, data.currentPage, data.maxPage);
    }

    public boolean isButtonsEnabled() {
        return getEffectiveMaxPages() > 1;
    }

    private void updateButtons(Player player, int currentPage, int maxPage) {
        PluginConfig cfg = configManager.getConfig();
        if (!isButtonsEnabled()) {
            player.getInventory().setItem(cfg.getPrevButtonSlot(), null);
            player.getInventory().setItem(cfg.getNextButtonSlot(), null);
            return;
        }
        boolean canPrev = currentPage > 0;
        boolean canNext = currentPage < getEffectiveMaxPages() - 1;
        player.getInventory().setItem(cfg.getPrevButtonSlot(),
                buttonManager.createPrevButton(currentPage, canPrev));
        player.getInventory().setItem(cfg.getNextButtonSlot(),
                buttonManager.createNextButton(currentPage, maxPage, canNext));
    }

    private void clearInventoryMain(Player player) {
        PluginConfig cfg = configManager.getConfig();
        int prevSlot = cfg.getPrevButtonSlot();
        int nextSlot = cfg.getNextButtonSlot();
        for (int i = 9; i <= 35; i++) {
            if (i != prevSlot && i != nextSlot) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private void loadItemsToInventory(Player player, Map<Integer, ItemStack> items) {
        if (items == null) return;
        items.forEach((slot, item) -> player.getInventory().setItem(slot, item));
    }

    // 工具

    public Map<Integer, ItemStack> snapshotCurrentPage(Player player) {
        PluginConfig cfg = configManager.getConfig();
        int prevSlot = cfg.getPrevButtonSlot();
        int nextSlot = cfg.getNextButtonSlot();
        Map<Integer, ItemStack> items = new HashMap<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 9; i <= 35; i++) {
            if (i == prevSlot || i == nextSlot) continue;
            ItemStack item = contents[i];
            if (item != null && !item.getType().isAir() && !buttonManager.isButton(item)) {
                items.put(i, item.clone());
            }
        }
        return items;
    }

    public int getEffectiveMaxPages() {
        int configured = configManager.getConfig().getMaxPages();
        if (configured <= 0) return MAX_PAGES_HARD_LIMIT;
        return Math.min(configured, MAX_PAGES_HARD_LIMIT);
    }

    private void snapshotToCache(Player player, PlayerPageData data) {
        Map<Integer, ItemStack> items = snapshotCurrentPage(player);
        if (!items.isEmpty() && data.currentPage > data.maxPage) {
            data.maxPage = data.currentPage;
        }
        data.cache.update(data.currentPage, items);
    }

    private PlayerPageData createPlayerData(UUID uuid, int currentPage, int maxPage) {
        PageCache cache = new PageCache(PAGE_CACHE_SIZE, (page, snapshot) ->
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        pageItemDao.savePage(uuid, page, snapshot);
                    } catch (SQLException e) {
                        plugin.getLogger().warning(messageManager.getLog(
                                MessageKeys.Log.PAGE_LRU_EVICT_FAILED,
                                "page", page + 1, "error", e.getMessage()));
                    }
                })
        );
        return new PlayerPageData(currentPage, maxPage, cache);
    }

    // 按钮槽位冲突
    public List<ItemStack> handleButtonSlotConflict(Player player) {
        UUID uuid = player.getUniqueId();
        PluginConfig cfg = configManager.getConfig();
        int prevSlot = cfg.getPrevButtonSlot();
        int nextSlot = cfg.getNextButtonSlot();
        List<ItemStack> unplaceable = new ArrayList<>();

        for (int slot : new int[]{prevSlot, nextSlot}) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir() || buttonManager.isButton(item)) continue;
            player.getInventory().setItem(slot, null);
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                PlayerPageData data = playerDataCache.get(uuid);
                int nextPage = (data != null ? data.maxPage : 0) + 1;
                if (nextPage < getEffectiveMaxPages()) {
                    Map<Integer, ItemStack> overflowMap = new HashMap<>();
                    int i = 9;
                    for (ItemStack overflow : leftover.values()) overflowMap.put(i++, overflow);
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            pageItemDao.savePage(uuid, nextPage, overflowMap);
                        } catch (SQLException e) {
                            plugin.getLogger().warning("按钮槽冲突物品保存失败: " + e.getMessage());
                        }
                    });
                    if (data != null) data.maxPage = Math.max(data.maxPage, nextPage);
                } else {
                    unplaceable.addAll(leftover.values());
                }
            }
        }
        return unplaceable;
    }

    // 跨页拾取
    /**
     * 跨页拾取：仅查缓存，不同步读 DB。
     * 缺页时请先调用 preloadPagesForPickup 触发异步预加载。
     *
     * @return 仍无法存放的物品数量（0 = 全部放下）
     */
    public int addItemAcrossPages(Player player, ItemStack item) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null || data.switching) return item.getAmount();

        PluginConfig cfg = configManager.getConfig();
        int prevSlot = cfg.getPrevButtonSlot();
        int nextSlot = cfg.getNextButtonSlot();

        int remaining = tryStackInCurrentPage(player, item, prevSlot, nextSlot);
        if (remaining <= 0) return 0;

        remaining = tryPlaceInCurrentPageEmpty(player, item, remaining, prevSlot, nextSlot);
        if (remaining <= 0) return 0;

        remaining = tryStackInCachedPages(uuid, item, remaining, data, prevSlot, nextSlot);
        if (remaining <= 0) return 0;

        remaining = tryPlaceInCachedPagesEmpty(uuid, item, remaining, data, prevSlot, nextSlot);
        return remaining;
    }

    /**
     * 跨页拾取前的异步预加载。
     * 将玩家 maxPage 以内的、缓存未命中的页面异步加载进缓存，
     * 使后续 addItemAcrossPages 的缓存命中率接近 100%。
     */
    public void preloadPagesForPickup(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) return;

        List<Integer> toLoad = new ArrayList<>();
        for (int page = 0; page <= data.maxPage; page++) {
            if (page != data.currentPage && data.cache.get(page) == null) {
                toLoad.add(page);
            }
        }
        if (toLoad.isEmpty()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<Integer, Map<Integer, ItemStack>> loaded = new LinkedHashMap<>();
            for (int page : toLoad) {
                try {
                    loaded.put(page, pageItemDao.loadPage(uuid, page));
                } catch (SQLException e) {
                    plugin.getLogger().warning("跨页预加载失败 page=" + page + ": " + e.getMessage());
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                for (Map.Entry<Integer, Map<Integer, ItemStack>> entry : loaded.entrySet()) {
                    if (data.cache.get(entry.getKey()) == null) {
                        data.cache.put(entry.getKey(), entry.getValue());
                    }
                }
            });
        });
    }

    private int tryStackInCurrentPage(Player player, ItemStack item, int prevSlot, int nextSlot) {
        int remaining = item.getAmount();
        int maxStack = item.getMaxStackSize();
        for (int slot = 9; slot <= 35 && remaining > 0; slot++) {
            if (slot == prevSlot || slot == nextSlot) continue;
            ItemStack existing = player.getInventory().getItem(slot);
            if (existing != null && existing.isSimilar(item) && existing.getAmount() < maxStack) {
                int toAdd = Math.min(remaining, maxStack - existing.getAmount());
                existing.setAmount(existing.getAmount() + toAdd);
                remaining -= toAdd;
            }
        }
        item.setAmount(remaining);
        return remaining;
    }

    private int tryPlaceInCurrentPageEmpty(Player player, ItemStack item, int remaining, int prevSlot, int nextSlot) {
        for (int slot = 9; slot <= 35 && remaining > 0; slot++) {
            if (slot == prevSlot || slot == nextSlot) continue;
            ItemStack existing = player.getInventory().getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                int toPlace = Math.min(remaining, item.getMaxStackSize());
                ItemStack newItem = item.clone();
                newItem.setAmount(toPlace);
                player.getInventory().setItem(slot, newItem);
                remaining -= toPlace;
            }
        }
        item.setAmount(remaining);
        return remaining;
    }

    private int tryStackInCachedPages(UUID uuid, ItemStack item, int remaining,
                                      PlayerPageData data, int prevSlot, int nextSlot) {
        for (int page = 0; page <= data.maxPage && remaining > 0; page++) {
            if (page == data.currentPage) continue;
            Map<Integer, ItemStack> pageItems = data.cache.get(page);
            if (pageItems == null) continue; // 缓存未命中，跳过

            boolean modified = false;
            for (Map.Entry<Integer, ItemStack> entry : pageItems.entrySet()) {
                int slot = entry.getKey();
                if (slot == prevSlot || slot == nextSlot) continue;
                ItemStack existing = entry.getValue();
                if (existing != null && existing.isSimilar(item) && existing.getAmount() < item.getMaxStackSize()) {
                    int toAdd = Math.min(remaining, item.getMaxStackSize() - existing.getAmount());
                    existing.setAmount(existing.getAmount() + toAdd);
                    remaining -= toAdd;
                    modified = true;
                }
                if (remaining <= 0) break;
            }
            // 就地修改了缓存 live map，只需标记脏
            if (modified) data.cache.markDirty(page);
        }
        item.setAmount(remaining);
        return remaining;
    }

    private int tryPlaceInCachedPagesEmpty(UUID uuid, ItemStack item, int remaining,
                                           PlayerPageData data, int prevSlot, int nextSlot) {
        int maxPageToCheck = Math.min(data.maxPage + 1, getEffectiveMaxPages() - 1);
        for (int page = 0; page <= maxPageToCheck && remaining > 0; page++) {
            if (page == data.currentPage) continue;
            Map<Integer, ItemStack> pageItems = data.cache.get(page);
            if (pageItems == null) continue;

            Set<Integer> occupied = pageItems.keySet();
            boolean hasEmpty = false;
            for (int slot = 9; slot <= 35; slot++) {
                if (slot != prevSlot && slot != nextSlot && !occupied.contains(slot)) {
                    hasEmpty = true;
                    break;
                }
            }
            if (!hasEmpty) continue;

            Map<Integer, ItemStack> modified = new HashMap<>(pageItems);
            for (int slot = 9; slot <= 35 && remaining > 0; slot++) {
                if (slot == prevSlot || slot == nextSlot) continue;
                if (!modified.containsKey(slot)) {
                    int toPlace = Math.min(remaining, item.getMaxStackSize());
                    ItemStack newItem = item.clone();
                    newItem.setAmount(toPlace);
                    modified.put(slot, newItem);
                    remaining -= toPlace;
                    if (page > data.maxPage) data.maxPage = page;
                }
            }
            data.cache.update(page, modified);
        }
        item.setAmount(remaining);
        return remaining;
    }

    // 跨页死亡掉落

    public Map<Integer, Map<Integer, ItemStack>> getAllPageItems(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        Map<Integer, Map<Integer, ItemStack>> allItems = new HashMap<>();
        if (data == null) return allItems;

        PluginConfig cfg = configManager.getConfig();
        int prevSlot = cfg.getPrevButtonSlot();
        int nextSlot = cfg.getNextButtonSlot();

        snapshotToCache(player, data);

        try {
            allItems = pageItemDao.loadAll(uuid);
        } catch (SQLException e) {
            plugin.getLogger().warning("加载玩家所有页面物品失败: " + e.getMessage());
        }

        // 用脏页缓存覆盖 DB 数据（脏页 = 有未写入的最新变更；非脏页与 DB 一致，无需覆盖）
        for (PageCache.DirtySnapshot s : data.cache.collectDirty()) {
            Map<Integer, ItemStack> filtered = new HashMap<>();
            for (Map.Entry<Integer, ItemStack> itemEntry : s.items().entrySet()) {
                int slot = itemEntry.getKey();
                if (slot == prevSlot || slot == nextSlot) continue;
                ItemStack item = itemEntry.getValue();
                if (item != null && !item.getType().isAir() && !buttonManager.isButton(item)) {
                    filtered.put(slot, item.clone());
                }
            }
            if (filtered.isEmpty()) allItems.remove(s.page());
            else allItems.put(s.page(), filtered);
        }
        return allItems;
    }

    public void clearAllPages(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) return;

        data.cache.clear();
        data.currentPage = 0;
        data.maxPage = 0;

        try {
            pageItemDao.clearAll(uuid);
            metaDao.update(uuid, 0, 0);
        } catch (SQLException e) {
            plugin.getLogger().warning("清空玩家所有页面物品失败: " + e.getMessage());
        }

        clearInventoryMain(player);
        updateButtons(player, 0, 0);
    }

    public PlayerPageData getPlayerData(UUID uuid) {
        return playerDataCache.get(uuid);
    }

    public int getCurrentPage(UUID uuid) {
        PlayerPageData data = playerDataCache.get(uuid);
        return data != null ? data.currentPage : 0;
    }

    public int getMaxPage(UUID uuid) {
        PlayerPageData data = playerDataCache.get(uuid);
        return data != null ? data.maxPage : 0;
    }
}
