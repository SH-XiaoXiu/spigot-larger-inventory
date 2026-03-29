package cn.xiuxius.mc.largerinventory.handover;

import cn.xiuxius.mc.largerinventory.database.HandoverDAO;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交接容器管理器
 * 负责管理强制重置后的物品交接
 */
public class HandoverContainerManager {

    // 每页物品数量（45格，留最后一行给导航）
    public static final int ITEMS_PER_PAGE = 45;
    // 上一页按钮槽位
    public static final int PREV_BUTTON_SLOT = 45;
    // 下一页按钮槽位
    public static final int NEXT_BUTTON_SLOT = 53;

    private final JavaPlugin plugin;
    private final MessageManager messageManager;
    private final HandoverDAO dao;
    // 玩家打开的交接容器缓存
    private final Map<UUID, Inventory> openContainers;
    // displaySlot(0-44) -> dbSlot(slot_index in DB)，每个玩家一张映射表
    private final Map<UUID, Map<Integer, Integer>> slotMapping;
    // 每个玩家当前在交接容器的第几页（0-indexed）
    private final Map<UUID, Integer> playerCurrentPage;

    public HandoverContainerManager(JavaPlugin plugin, MessageManager messageManager, HandoverDAO dao) {
        this.plugin = plugin;
        this.messageManager = messageManager;
        this.dao = dao;
        this.openContainers = new HashMap<>();
        this.slotMapping = new HashMap<>();
        this.playerCurrentPage = new HashMap<>();
    }

    /**
     * 创建交接容器（在线玩家）
     */
    public boolean createContainer(Player player, List<ItemStack> items) {
        return createContainer(player.getUniqueId(), player.getName(), items);
    }

    /**
     * 创建交接容器（支持离线玩家）
     *
     * @param uuid       玩家UUID
     * @param playerName 玩家名称（用于日志）
     * @param items      要存入的物品
     * @return 是否创建成功
     */
    public boolean createContainer(UUID uuid, String playerName, List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }

        try {
            dao.append(uuid, items);
            plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.HANDOVER_CREATED, "player", playerName, "count", items.size()));
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.HANDOVER_CREATE_FAILED, "error", e.getMessage()));
            return false;
        }
    }

    /**
     * 打开交接容器（默认第0页）
     *
     * @param player 玩家
     * @return 是否成功打开
     */
    public boolean openContainer(Player player) {
        return openContainer(player, 0);
    }

    /**
     * 打开交接容器的指定页
     *
     * @param player 玩家
     * @param page   页码（0-indexed）
     * @return 是否成功打开
     */
    public boolean openContainer(Player player, int page) {
        UUID uuid = player.getUniqueId();
        try {
            if (dao.isEmpty(uuid)) {
                player.sendMessage(messageManager.get(MessageKeys.Handover.NO_ITEMS));
                return false;
            }

            Map<Integer, ItemStack> allItemsMap = dao.load(uuid);
            List<Map.Entry<Integer, ItemStack>> sortedEntries = new ArrayList<>(allItemsMap.entrySet());
            sortedEntries.sort(Comparator.comparingInt(Map.Entry::getKey));

            int totalItems = sortedEntries.size();
            int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE));
            page = Math.max(0, Math.min(page, totalPages - 1));

            // 创建容器，标题显示当前页/总页数
            String title = messageManager.get(MessageKeys.Handover.CONTAINER_TITLE, "page", page + 1, "total", totalPages);
            Inventory container = Bukkit.createInventory(null, 54, title);

            // 填充物品区（槽位 0-44），建立映射表
            Map<Integer, Integer> playerSlotMap = new HashMap<>();
            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, totalItems);
            for (int i = start; i < end; i++) {
                int displaySlot = i - start;
                Map.Entry<Integer, ItemStack> entry = sortedEntries.get(i);
                container.setItem(displaySlot, entry.getValue());
                playerSlotMap.put(displaySlot, entry.getKey()); // displaySlot -> dbSlot
            }
            slotMapping.put(uuid, playerSlotMap);
            playerCurrentPage.put(uuid, page);

            // 填充导航行（槽位 45-53）
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fillerMeta = filler.getItemMeta();
            fillerMeta.setDisplayName(messageManager.get(MessageKeys.Handover.NAV_PAGE_INFO, "page", page + 1, "total", totalPages));
            filler.setItemMeta(fillerMeta);
            for (int s = 45; s <= 53; s++) {
                container.setItem(s, filler);
            }

            // 上一页按钮
            if (page > 0) {
                ItemStack prev = new ItemStack(Material.ARROW);
                ItemMeta prevMeta = prev.getItemMeta();
                prevMeta.setDisplayName(messageManager.get(MessageKeys.Handover.NAV_PREV_NAME));
                prev.setItemMeta(prevMeta);
                container.setItem(PREV_BUTTON_SLOT, prev);
            }

            // 下一页按钮
            if (page < totalPages - 1) {
                ItemStack next = new ItemStack(Material.ARROW);
                ItemMeta nextMeta = next.getItemMeta();
                nextMeta.setDisplayName(messageManager.get(MessageKeys.Handover.NAV_NEXT_NAME));
                next.setItemMeta(nextMeta);
                container.setItem(NEXT_BUTTON_SLOT, next);
            }

            openContainers.put(uuid, container);
            player.openInventory(container);

            if (page == 0) {
                player.sendMessage(messageManager.get(MessageKeys.Handover.OPENED));
                player.sendMessage(messageManager.get(MessageKeys.Handover.NOTE_READONLY));
            }
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.HANDOVER_OPEN_FAILED, "error", e.getMessage()));
            player.sendMessage(messageManager.get(MessageKeys.Handover.OPEN_FAILED));
            return false;
        }
    }

    /**
     * 翻页
     *
     * @param player 玩家
     * @param delta  页数变化（+1 或 -1）
     */
    public void changePage(Player player, int delta) {
        int current = playerCurrentPage.getOrDefault(player.getUniqueId(), 0);
        int newPage = current + delta;
        // 延迟1tick：避免在 InventoryClickEvent 中直接操作 inventory
        Bukkit.getScheduler().runTaskLater(plugin, () -> openContainer(player, newPage), 1L);
    }

    /**
     * 检查是否为交接容器
     *
     * @param inventory 物品栏
     * @return 是否为交接容器
     */
    public boolean isHandoverContainer(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        // 通过缓存判断是否为交接容器
        return openContainers.containsValue(inventory);
    }

    /**
     * 物品被取出时更新数据库
     *
     * @param player  玩家
     * @param rawSlot 槽位
     */
    public void onItemTaken(Player player, int rawSlot) {
        if (rawSlot >= ITEMS_PER_PAGE) return; // 导航槽位，忽略
        UUID uuid = player.getUniqueId();
        try {
            Map<Integer, Integer> playerSlotMap = slotMapping.get(uuid);
            if (playerSlotMap == null) {
                dao.remove(uuid, rawSlot); // 兜底
                return;
            }
            Integer dbSlot = playerSlotMap.get(rawSlot);
            if (dbSlot == null) return;
            dao.remove(uuid, dbSlot);
            playerSlotMap.remove(rawSlot);
            plugin.getLogger().fine(messageManager.getLog(MessageKeys.Log.HANDOVER_ITEM_TAKEN, "player", player.getName(), "slot", rawSlot));
        } catch (SQLException e) {
            plugin.getLogger().warning(messageManager.getLog(MessageKeys.Log.HANDOVER_UPDATE_FAILED, "error", e.getMessage()));
        }
    }

    /**
     * 检查交接容器是否为空
     *
     * @param player 玩家
     * @return 是否为空
     */
    public boolean isContainerEmpty(Player player) {
        try {
            return dao.isEmpty(player.getUniqueId());
        } catch (SQLException e) {
            return true;
        }
    }

    /**
     * 销毁交接容器（当物品全部取出后）
     *
     * @param player 玩家
     */
    public void destroyContainer(Player player) {
        UUID uuid = player.getUniqueId();
        try {
            dao.clear(uuid);
            openContainers.remove(uuid);
            slotMapping.remove(uuid);
            playerCurrentPage.remove(uuid);
            plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.HANDOVER_DESTROYED, "player", player.getName()));
            player.sendMessage(messageManager.get(MessageKeys.Handover.ALL_RETRIEVED));
        } catch (SQLException e) {
            plugin.getLogger().severe(messageManager.getLog(MessageKeys.Log.HANDOVER_UPDATE_FAILED, "error", e.getMessage()));
        }
    }

    /**
     * 玩家关闭容器时检查是否需要销毁
     *
     * @param player 玩家
     */
    public void onClose(Player player) {
        UUID uuid = player.getUniqueId();
        openContainers.remove(uuid);
        slotMapping.remove(uuid);

        // 检查是否为空
        try {
            if (dao.isEmpty(uuid)) {
                dao.clear(uuid);
                playerCurrentPage.remove(uuid);
                plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.HANDOVER_AUTO_DESTROYED, "player", player.getName()));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning(messageManager.getLog(MessageKeys.Log.HANDOVER_STATUS_CHECK_FAILED, "error", e.getMessage()));
        }
    }

    /**
     * 检查玩家是否有交接容器
     *
     * @param uuid 玩家UUID
     * @return 是否有交接容器
     */
    public boolean hasContainer(UUID uuid) {
        try {
            return !dao.isEmpty(uuid);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 获取交接容器中的物品数量
     *
     * @param uuid 玩家UUID
     * @return 物品数量
     */
    public int getContainerItemCount(UUID uuid) {
        try {
            Map<Integer, ItemStack> items = dao.load(uuid);
            return items.size();
        } catch (SQLException e) {
            return 0;
        }
    }
}
