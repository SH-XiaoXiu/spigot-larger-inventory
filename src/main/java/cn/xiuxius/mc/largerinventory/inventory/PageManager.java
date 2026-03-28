package cn.xiuxius.mc.largerinventory.inventory;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.database.PlayerInventoryDAO;
import cn.xiuxius.mc.largerinventory.database.model.PlayerMeta;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 分页管理器（内存优先 + 写回缓存模型）
 * - 所有翻页操作在内存中即时完成
 * - 每个玩家维护一个 LRU 页面缓存，容量为 PAGE_CACHE_SIZE
 * - 脏页由定时器批量异步刷入 DB（写入聚合），LRU 驱逐时也触发异步写
 * - 玩家退出时同步 flush，确保数据安全
 * - 只有放入物品才算真正创建，纯翻页不更新 maxPage
 */
public class PageManager {

    /**
     * 代码层面的绝对上限，防止配置填写离谱数值
     */
    public static final int MAX_PAGES_HARD_LIMIT = 99;

    /**
     * 每个玩家的 LRU 页面缓存大小
     */
    private static final int PAGE_CACHE_SIZE = 6;

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final ButtonManager buttonManager;
    private final MessageManager messageManager;
    private final PlayerInventoryDAO dao;

    private final Map<UUID, PlayerPageData> playerDataCache = new ConcurrentHashMap<>();

    public PageManager(JavaPlugin plugin, ConfigManager configManager,
                       ButtonManager buttonManager, MessageManager messageManager, PlayerInventoryDAO dao) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.buttonManager = buttonManager;
        this.messageManager = messageManager;
        this.dao = dao;
    }

    //内部类 

    /**
     * 单页缓存条目
     */
    static class CachedPage {
        final Map<Integer, ItemStack> items;
        volatile boolean dirty;

        CachedPage(Map<Integer, ItemStack> items, boolean dirty) {
            this.items = items;
            this.dirty = dirty;
        }

        boolean hasItems() {
            return !items.isEmpty();
        }
    }

    /**
     * 基于 LinkedHashMap 的 LRU 缓存。
     * accessOrder=true 保证最近访问的条目最晚被驱逐。
     * 驱逐脏页时自动触发异步 DB 写入。
     */
    static class PageLRUCache extends LinkedHashMap<Integer, CachedPage> {
        private final int capacity;
        private final BiConsumer<Integer, Map<Integer, ItemStack>> onDirtyEvict;

        PageLRUCache(int capacity, BiConsumer<Integer, Map<Integer, ItemStack>> onDirtyEvict) {
            super(capacity + 1, 0.75f, true);
            this.capacity = capacity;
            this.onDirtyEvict = onDirtyEvict;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, CachedPage> eldest) {
            if (size() > capacity) {
                CachedPage page = eldest.getValue();
                if (page.dirty && page.hasItems()) {
                    // 快照后再异步写，避免驱逐后 items map 被引用
                    onDirtyEvict.accept(eldest.getKey(), new HashMap<>(page.items));
                }
                return true;
            }
            return false;
        }
    }

    /**
     * 玩家运行时状态
     */
    public static class PlayerPageData {
        public volatile int currentPage;
        public int maxPage;        // 有内容的最高页码（仅在保存非空页时更新）
        public volatile boolean switching;
        final PageLRUCache cache;

        PlayerPageData(int currentPage, int maxPage, PageLRUCache cache) {
            this.currentPage = currentPage;
            this.maxPage = maxPage;
            this.cache = cache;
        }
    }

    /**
     * 用于批量刷脏的临时任务描述
     */
    private static class WriteTask {
        final UUID uuid;
        final int pageNum;
        final Map<Integer, ItemStack> items;
        final CachedPage source; // 写入成功后清 dirty 标记

        WriteTask(UUID uuid, int pageNum, Map<Integer, ItemStack> items, CachedPage source) {
            this.uuid = uuid;
            this.pageNum = pageNum;
            this.items = items;
            this.source = source;
        }
    }

    //生命周期 

    /**
     * 玩家加入：仅加载当前页，其余页懒加载
     */
    public void initPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        try {
            dao.createOrUpdatePlayerMeta(uuid, player.getName());
            PlayerMeta meta = dao.getPlayerMeta(uuid);

            int currentPage = meta != null ? meta.getCurrentPage() : 0;
            int maxPage = meta != null ? meta.getMaxPage() : 0;

            PlayerPageData data = createPlayerData(uuid, currentPage, maxPage);
            playerDataCache.put(uuid, data);

            Map<Integer, ItemStack> items = dao.loadPageItems(uuid, currentPage);
            data.cache.put(currentPage, new CachedPage(new HashMap<>(items), false));
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

    /**
     * 玩家退出：同步 flush 所有脏页，确保数据不丢失
     */
    public void saveAndClearPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) return;

        snapshotToCache(player, data);
        flushDirtyPagesSync(uuid, data);
        playerDataCache.remove(uuid);
    }

    //翻页逻辑 

    public void prevPage(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data != null && data.currentPage > 0) {
            switchToPage(player, data.currentPage - 1);
        }
    }

    public void nextPage(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
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

    /**
     * 切换到目标页。
     * 优先命中 LRU 缓存；缓存未命中时异步从 DB 加载。
     * 前一页立即写入缓存（脏），不阻塞主线程。
     */
    public void switchToPage(Player player, int targetPage) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null || data.switching) return;
        if (targetPage < 0 || targetPage >= getEffectiveMaxPages()) return;

        data.switching = true;

        // 将当前页内容快照进缓存（标脏）
        snapshotToCache(player, data);

        int prevPage = data.currentPage;
        data.currentPage = targetPage;

        // 立即清空背包 + 更新按钮（UI 即时响应，不等 IO）
        clearInventoryMain(player);
        updateButtons(player, targetPage, data.maxPage);

        // 尝试命中缓存
        CachedPage cached = data.cache.get(targetPage);
        if (cached != null) {
            loadItemsToInventory(player, cached.items);
            updateButtons(player, targetPage, data.maxPage);
            data.switching = false;
            return;
        }

        // 缓存未命中：异步从 DB 加载
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<Integer, ItemStack> items = dao.loadPageItems(uuid, targetPage);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    data.switching = false;
                    if (!player.isOnline()) return;
                    data.cache.put(targetPage, new CachedPage(new HashMap<>(items), false));
                    loadItemsToInventory(player, items);
                    updateButtons(player, targetPage, data.maxPage);
                });
            } catch (SQLException e) {
                plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.PAGE_LOAD_FAILED,
                        "uuid", uuid, "page", targetPage + 1, "error", e.getMessage()));
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    data.switching = false;
                    // 回退到之前的页码（加载失败）
                    data.currentPage = prevPage;
                    updateButtons(player, prevPage, data.maxPage);
                });
            }
        });
    }

    //持久化 

    /**
     * 定时刷脏入口（在主线程调用）。
     * 先快照所有玩家的当前页，然后收集脏页，最后异步批量写 DB。
     * 短时间内的多次操作自然聚合为一次写入。
     */
    public void flushAllDirtyPages() {
        // 快照当前页（必须主线程）
        for (Map.Entry<UUID, PlayerPageData> entry : playerDataCache.entrySet()) {
            PlayerPageData data = entry.getValue();
            if (data.switching) continue;
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                snapshotToCache(player, data);
            }
        }

        // 收集脏页快照
        List<WriteTask> tasks = new ArrayList<>();
        Map<UUID, int[]> metas = new LinkedHashMap<>(); // uuid -> [currentPage, maxPage]

        for (Map.Entry<UUID, PlayerPageData> entry : playerDataCache.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerPageData data = entry.getValue();
            boolean hasDirty = false;
            for (Map.Entry<Integer, CachedPage> pageEntry : data.cache.entrySet()) {
                CachedPage page = pageEntry.getValue();
                if (page.dirty) {
                    tasks.add(new WriteTask(uuid, pageEntry.getKey(), new HashMap<>(page.items), page));
                    hasDirty = true;
                }
            }
            if (hasDirty) {
                metas.put(uuid, new int[]{data.currentPage, data.maxPage});
            }
        }

        if (tasks.isEmpty()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            for (WriteTask task : tasks) {
                try {
                    dao.savePageItems(task.uuid, task.pageNum, task.items);
                    task.source.dirty = false; // 写入成功后清标记
                } catch (SQLException e) {
                    plugin.getLogger().warning(messageManager.getLog(MessageKeys.Log.PAGE_WRITE_FAILED,
                            "page", task.pageNum + 1, "error", e.getMessage()));
                }
            }
            Set<UUID> done = new HashSet<>();
            for (WriteTask task : tasks) {
                if (done.add(task.uuid)) {
                    int[] meta = metas.get(task.uuid);
                    try {
                        dao.updatePlayerMeta(task.uuid, meta[0], meta[1]);
                    } catch (SQLException e) {
                        plugin.getLogger().warning(messageManager.getLog(MessageKeys.Log.PAGE_METADATA_UPDATE_FAILED,
                                "error", e.getMessage()));
                    }
                }
            }
        });
    }

    /**
     * 供 AdminCommand 等场景手动触发单玩家保存
     */
    public void saveCurrentPage(Player player) throws SQLException {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) return;
        Map<Integer, ItemStack> items = snapshotCurrentPage(player);
        dao.savePageItems(uuid, data.currentPage, items);
    }

    /**
     * 快照当前页内容到 LRU 缓存（标脏）。
     * 只有快照到非空页面时才更新 maxPage，实现"有内容才创建页"语义。
     */
    private void snapshotToCache(Player player, PlayerPageData data) {
        Map<Integer, ItemStack> items = snapshotCurrentPage(player);
        if (!items.isEmpty() && data.currentPage > data.maxPage) {
            data.maxPage = data.currentPage;
        }
        CachedPage existing = data.cache.get(data.currentPage);
        if (existing != null) {
            // 复用条目，直接更新内容（避免触发不必要的 LRU 调整）
            existing.items.clear();
            existing.items.putAll(items);
            existing.dirty = true;
        } else {
            data.cache.put(data.currentPage, new CachedPage(new HashMap<>(items), true));
        }
    }

    /**
     * 同步刷所有脏页（玩家退出/服务器关闭时调用）
     */
    private void flushDirtyPagesSync(UUID uuid, PlayerPageData data) {
        try {
            for (Map.Entry<Integer, CachedPage> entry : data.cache.entrySet()) {
                CachedPage page = entry.getValue();
                if (page.dirty) {
                    dao.savePageItems(uuid, entry.getKey(), page.items);
                    page.dirty = false;
                }
            }
            dao.updatePlayerMeta(uuid, data.currentPage, data.maxPage);
        } catch (SQLException e) {
            plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.PLAYER_DATA_SAVE_FAILED,
                    "uuid", uuid, "error", e.getMessage()));
        }
    }

    /**
     * 异步写入单页（LRU 驱逐时调用）
     */
    private void asyncSavePage(UUID uuid, int pageNum, Map<Integer, ItemStack> items) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                dao.savePageItems(uuid, pageNum, items);
            } catch (SQLException e) {
                plugin.getLogger().warning(messageManager.getLog(MessageKeys.Log.PAGE_LRU_EVICT_FAILED,
                        "page", pageNum + 1, "error", e.getMessage()));
            }
        });
    }

    //UI 相关 

    /**
     * 从缓存（或 DB）重载当前页，不快照现有背包内容。
     * 用于从创造模式切换回来时恢复受插件管控的背包状态。
     */
    public void reloadCurrentPage(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null || data.switching) return;

        clearInventoryMain(player);
        CachedPage cached = data.cache.get(data.currentPage);
        if (cached != null) {
            loadItemsToInventory(player, cached.items);
            updateButtons(player, data.currentPage, data.maxPage);
        } else {
            // 缓存未命中，异步从 DB 加载
            data.switching = true;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Map<Integer, ItemStack> items = dao.loadPageItems(uuid, data.currentPage);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        data.switching = false;
                        if (!player.isOnline()) return;
                        data.cache.put(data.currentPage, new CachedPage(new HashMap<>(items), false));
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

    /**
     * 恢复按钮到正确位置（供事件监听器调用，防止排序模组移动按钮）
     */
    public void restoreButtons(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        if (data == null) return;
        updateButtons(player, data.currentPage, data.maxPage);
    }

    private void updateButtons(Player player, int currentPage, int maxPage) {
        if (!isButtonsEnabled()) {
            player.getInventory().setItem(configManager.getPrevButtonSlot(), null);
            player.getInventory().setItem(configManager.getNextButtonSlot(), null);
            return;
        }
        boolean canPrev = currentPage > 0;
        boolean canNext = currentPage < getEffectiveMaxPages() - 1;
        player.getInventory().setItem(configManager.getPrevButtonSlot(),
                buttonManager.createPrevButton(currentPage, canPrev));
        player.getInventory().setItem(configManager.getNextButtonSlot(),
                buttonManager.createNextButton(currentPage, maxPage, canNext));
    }

    /**
     * 当有效最大页数 > 1 时才显示并保护按钮槽位
     */
    public boolean isButtonsEnabled() {
        return getEffectiveMaxPages() > 1;
    }

    private void clearInventoryMain(Player player) {
        int prevSlot = configManager.getPrevButtonSlot();
        int nextSlot = configManager.getNextButtonSlot();
        for (int i = 9; i <= 35; i++) {
            if (i != prevSlot && i != nextSlot) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private void loadItemsToInventory(Player player, Map<Integer, ItemStack> items) {
        if (items == null) return;
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            player.getInventory().setItem(entry.getKey(), entry.getValue());
        }
    }

    //工具方法 

    /**
     * 快照当前页物品（主线程调用）。
     * 返回的 map 是独立副本，可安全传递给异步线程。
     */
    public Map<Integer, ItemStack> snapshotCurrentPage(Player player) {
        int prevSlot = configManager.getPrevButtonSlot();
        int nextSlot = configManager.getNextButtonSlot();
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

    /**
     * 返回实际生效的最大页数（受配置和硬上限双重约束）
     */
    public int getEffectiveMaxPages() {
        int configured = configManager.getMaxPages();
        if (configured <= 0) return MAX_PAGES_HARD_LIMIT;
        return Math.min(configured, MAX_PAGES_HARD_LIMIT);
    }

    private PlayerPageData createPlayerData(UUID uuid, int currentPage, int maxPage) {
        BiConsumer<Integer, Map<Integer, ItemStack>> evictHandler =
                (pageNum, items) -> asyncSavePage(uuid, pageNum, items);
        return new PlayerPageData(currentPage, maxPage,
                new PageLRUCache(PAGE_CACHE_SIZE, evictHandler));
    }

    /**
     * 处理按钮槽位冲突。
     *
     * @return 无法放入背包也无法存入新页的物品（调用方应送交接容器）
     */
    public List<ItemStack> handleButtonSlotConflict(Player player) {
        UUID uuid = player.getUniqueId();
        int prevSlot = configManager.getPrevButtonSlot();
        int nextSlot = configManager.getNextButtonSlot();
        List<ItemStack> unplaceable = new ArrayList<>();

        for (int slot : new int[]{prevSlot, nextSlot}) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && !item.getType().isAir() && !buttonManager.isButton(item)) {
                player.getInventory().setItem(slot, null);
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    PlayerPageData data = playerDataCache.get(uuid);
                    int nextPage = (data != null ? data.maxPage : 0) + 1;
                    if (nextPage < getEffectiveMaxPages()) {
                        Map<Integer, ItemStack> overflowMap = new HashMap<>();
                        int i = 9;
                        for (ItemStack overflow : leftover.values()) {
                            overflowMap.put(i++, overflow);
                        }
                        asyncSavePage(uuid, nextPage, overflowMap);
                        if (data != null) data.maxPage = Math.max(data.maxPage, nextPage);
                    } else {
                        // 页数已满，无处可存，返给调用方走交接容器
                        unplaceable.addAll(leftover.values());
                    }
                }
            }
        }
        return unplaceable;
    }

    //访问器（供外部使用） 

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
