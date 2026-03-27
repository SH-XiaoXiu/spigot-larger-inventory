package cn.xiuxius.mc.largerinventory.inventory;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.database.PlayerInventoryDAO;
import cn.xiuxius.mc.largerinventory.database.model.PlayerMeta;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分页管理器
 * 负责背包分页的核心逻辑
 */
public class PageManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final ButtonManager buttonManager;
    private final PlayerInventoryDAO dao;

    // 玩家数据缓存
    private final Map<UUID, PlayerPageData> playerDataCache;

    public PageManager(JavaPlugin plugin, ConfigManager configManager, ButtonManager buttonManager, PlayerInventoryDAO dao) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.buttonManager = buttonManager;
        this.dao = dao;
        this.playerDataCache = new ConcurrentHashMap<>();
    }

    /**
     * 初始化玩家数据
     */
    public void initPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        try {
            // 创建或更新玩家元数据
            dao.createOrUpdatePlayerMeta(uuid, playerName);

            // 加载玩家元数据
            PlayerMeta meta = dao.getPlayerMeta(uuid);
            int currentPage;
            int maxPage;

            if (meta != null) {
                currentPage = meta.getCurrentPage();
                maxPage = meta.getMaxPage();
            } else {
                currentPage = 0;
                maxPage = 0;
            }

            // 检查是否有超页数据
            int configMaxPages = configManager.getMaxPages();
            boolean hasOverflow = configMaxPages > 0 && maxPage >= configMaxPages;

            // 缓存数据
            playerDataCache.put(uuid, new PlayerPageData(currentPage, maxPage, hasOverflow));

            // 加载当前页物品
            Map<Integer, ItemStack> items = dao.loadPageItems(uuid, currentPage);
            loadItemsToInventory(player, items);

            // 设置按钮
            updateButtons(player, currentPage, maxPage, hasOverflow);

            plugin.getLogger().info("玩家 " + player.getName() + " 的背包数据已加载，当前页: " + currentPage + ", 最大页: " + maxPage);
        } catch (SQLException e) {
            plugin.getLogger().severe("加载玩家 " + player.getName() + " 数据失败: " + e.getMessage());
            // 创建默认数据
            playerDataCache.put(uuid, new PlayerPageData(0, 0, false));
            updateButtons(player, 0, 0, false);
        }
    }

    /**
     * 保存并清理玩家数据
     */
    public void saveAndClearPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) {
            return;
        }

        try {
            // 保存当前页物品
            saveCurrentPage(player);
        } catch (SQLException e) {
            plugin.getLogger().severe("保存玩家 " + player.getName() + " 数据失败: " + e.getMessage());
        }

        // 清理缓存
        playerDataCache.remove(uuid);
    }

    /**
     * 保存当前页物品
     */
    public void saveCurrentPage(Player player) throws SQLException {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) {
            return;
        }

        int currentPage = data.currentPage;
        int prevSlot = configManager.getPrevButtonSlot();
        int nextSlot = configManager.getNextButtonSlot();

        // 收集物品（排除按钮位置）
        Map<Integer, ItemStack> items = new HashMap<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 9; i <= 35; i++) { // 只保存背包主体（9-35）
            if (i != prevSlot && i != nextSlot) {
                ItemStack item = contents[i];
                if (item != null && !item.getType().isAir() && !buttonManager.isButton(item)) {
                    items.put(i, item.clone());
                }
            }
        }

        // 保存到数据库
        dao.savePageItems(uuid, currentPage, items);
    }

    /**
     * 翻到上一页
     */
    public void prevPage(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null || data.currentPage <= 0) {
            return;
        }

        switchToPage(player, data.currentPage - 1);
    }

    /**
     * 翻到下一页
     */
    public void nextPage(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) {
            return;
        }

        // 检查是否可以创建新页
        if (!canCreateNewPage(player)) {
            return;
        }

        switchToPage(player, data.currentPage + 1);
    }

    /**
     * 切换到指定页
     */
    public void switchToPage(Player player, int targetPage) {
        UUID uuid = player.getUniqueId();
        PlayerPageData data = playerDataCache.get(uuid);
        if (data == null) {
            return;
        }

        try {
            // 保存当前页
            saveCurrentPage(player);

            // 清空背包（保留快捷栏、装备、副手）
            clearInventoryMain(player);

            // 加载目标页物品
            Map<Integer, ItemStack> items = dao.loadPageItems(uuid, targetPage);
            loadItemsToInventory(player, items);

            // 更新元数据
            int newMaxPage = Math.max(data.maxPage, targetPage);
            data.currentPage = targetPage;
            data.maxPage = newMaxPage;
            dao.updatePlayerMeta(uuid, targetPage, newMaxPage);

            // 更新按钮
            updateButtons(player, targetPage, newMaxPage, data.hasOverflow);

            plugin.getLogger().fine("玩家 " + player.getName() + " 翻页到第 " + (targetPage + 1) + " 页");
        } catch (SQLException e) {
            plugin.getLogger().severe("玩家 " + player.getName() + " 翻页失败: " + e.getMessage());
        }
    }

    /**
     * 检查是否可以创建新页
     */
    public boolean canCreateNewPage(Player player) {
        int maxPages = configManager.getMaxPages();
        if (maxPages <= 0) {
            return true; // 无限制
        }

        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        if (data == null) {
            return true;
        }

        // 如果当前已达上限，检查是否有超页数据
        if (data.currentPage >= maxPages - 1) {
            // 如果有超页数据，允许翻过去查看
            if (data.hasOverflow) {
                return true;
            }
            return false;
        }

        return true;
    }

    /**
     * 检查是否可以向前翻
     */
    public boolean canPrevPage(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        return data != null && data.currentPage > 0;
    }

    /**
     * 检查是否可以向后翻
     */
    public boolean canNextPage(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        if (data == null) {
            return false;
        }

        // 如果有超页数据，总是允许向后
        if (data.hasOverflow && data.currentPage < data.maxPage) {
            return true;
        }

        // 否则检查是否可以创建新页
        return canCreateNewPage(player);
    }

    /**
     * 恢复按钮到正确位置（供事件监听器在排序模组操作后调用）
     */
    public void restoreButtons(Player player) {
        PlayerPageData data = playerDataCache.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        updateButtons(player, data.currentPage, data.maxPage, data.hasOverflow);
    }

    /**
     * 更新按钮显示
     */
    private void updateButtons(Player player, int currentPage, int maxPage, boolean hasOverflow) {
        boolean canPrev = currentPage > 0;
        boolean canNext = canNextPage(player);

        ItemStack prevButton = buttonManager.createPrevButton(currentPage, canPrev);
        ItemStack nextButton = buttonManager.createNextButton(currentPage, maxPage, canNext);

        player.getInventory().setItem(configManager.getPrevButtonSlot(), prevButton);
        player.getInventory().setItem(configManager.getNextButtonSlot(), nextButton);
    }

    /**
     * 清空背包主体部分（槽位9-35）
     */
    private void clearInventoryMain(Player player) {
        int prevSlot = configManager.getPrevButtonSlot();
        int nextSlot = configManager.getNextButtonSlot();

        for (int i = 9; i <= 35; i++) {
            if (i != prevSlot && i != nextSlot) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    /**
     * 加载物品到背包
     */
    private void loadItemsToInventory(Player player, Map<Integer, ItemStack> items) {
        if (items == null) {
            return;
        }
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            player.getInventory().setItem(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 处理按钮位置冲突
     * 当插件启动时，检查按钮位置是否有物品，如果有则迁移
     */
    public void handleButtonSlotConflict(Player player) {
        UUID uuid = player.getUniqueId();
        int prevSlot = configManager.getPrevButtonSlot();
        int nextSlot = configManager.getNextButtonSlot();

        ItemStack prevItem = player.getInventory().getItem(prevSlot);
        ItemStack nextItem = player.getInventory().getItem(nextSlot);

        List<ItemStack> conflictItems = new ArrayList<>();
        if (prevItem != null && !prevItem.getType().isAir() && !buttonManager.isButton(prevItem)) {
            conflictItems.add(prevItem);
            player.getInventory().setItem(prevSlot, null);
        }
        if (nextItem != null && !nextItem.getType().isAir() && !buttonManager.isButton(nextItem)) {
            conflictItems.add(nextItem);
            player.getInventory().setItem(nextSlot, null);
        }

        if (!conflictItems.isEmpty()) {
            // 尝试将冲突物品放入背包空位
            for (ItemStack item : conflictItems) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    // 背包满了，存到下一页
                    try {
                        PlayerPageData data = playerDataCache.get(uuid);
                        int nextPage = (data != null ? data.maxPage : 0) + 1;
                        List<ItemStack> overflowItems = new ArrayList<>(leftover.values());
                        for (int i = 0; i < overflowItems.size(); i++) {
                            Map<Integer, ItemStack> pageItems = new HashMap<>();
                            pageItems.put(i, overflowItems.get(i));
                            dao.savePageItems(uuid, nextPage, pageItems);
                        }
                        if (data != null) {
                            data.maxPage = Math.max(data.maxPage, nextPage);
                            dao.updateMaxPage(uuid, data.maxPage);
                        }
                        plugin.getLogger().info("玩家 " + player.getName() + " 的按钮位置冲突物品已移至第 " + (nextPage + 1) + " 页");
                    } catch (SQLException e) {
                        plugin.getLogger().severe("处理按钮位置冲突失败: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 获取玩家分页数据
     */
    public PlayerPageData getPlayerData(UUID uuid) {
        return playerDataCache.get(uuid);
    }

    /**
     * 获取玩家当前页
     */
    public int getCurrentPage(UUID uuid) {
        PlayerPageData data = playerDataCache.get(uuid);
        return data != null ? data.currentPage : 0;
    }

    /**
     * 获取玩家最大页
     */
    public int getMaxPage(UUID uuid) {
        PlayerPageData data = playerDataCache.get(uuid);
        return data != null ? data.maxPage : 0;
    }

    /**
     * 玩家分页数据
     */
    public static class PlayerPageData {
        public int currentPage;
        public int maxPage;
        public boolean hasOverflow; // 是否有超页数据

        public PlayerPageData(int currentPage, int maxPage, boolean hasOverflow) {
            this.currentPage = currentPage;
            this.maxPage = maxPage;
            this.hasOverflow = hasOverflow;
        }
    }
}
