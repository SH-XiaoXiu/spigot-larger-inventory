package cn.xiuxius.mc.largerinventory.handover;

import cn.xiuxius.mc.largerinventory.database.PlayerInventoryDAO;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交接容器管理器
 * 负责管理强制重置后的物品交接
 */
public class HandoverContainerManager {

    // 容器标题
    private static final String CONTAINER_TITLE = ChatColor.GOLD + "物品交接容器";
    private final JavaPlugin plugin;
    private final PlayerInventoryDAO dao;
    // 玩家打开的交接容器缓存
    private final Map<UUID, Inventory> openContainers;

    public HandoverContainerManager(JavaPlugin plugin, PlayerInventoryDAO dao) {
        this.plugin = plugin;
        this.dao = dao;
        this.openContainers = new HashMap<>();
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
            dao.createHandoverItems(uuid, items);
            plugin.getLogger().info("为玩家 " + playerName + " 创建交接容器，共 " + items.size() + " 个物品");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("创建交接容器失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 打开交接容器
     *
     * @param player 玩家
     * @return 是否成功打开
     */
    public boolean openContainer(Player player) {
        UUID uuid = player.getUniqueId();
        try {
            // 检查是否有交接容器
            if (dao.isHandoverContainerEmpty(uuid)) {
                player.sendMessage(ChatColor.YELLOW + "你没有待领取的物品。");
                return false;
            }

            // 加载物品
            Map<Integer, ItemStack> items = dao.loadHandoverItems(uuid);

            // 创建虚拟容器
            Inventory container = Bukkit.createInventory(null, 54, CONTAINER_TITLE);

            // 填充物品
            for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
                if (entry.getKey() < 54) {
                    container.setItem(entry.getKey(), entry.getValue());
                }
            }

            // 缓存并打开
            openContainers.put(uuid, container);
            player.openInventory(container);

            player.sendMessage(ChatColor.GREEN + "已打开交接容器，请取出你的物品。");
            player.sendMessage(ChatColor.YELLOW + "注意：只能取出物品，不能放入物品。");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("打开交接容器失败: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "打开交接容器失败，请联系管理员。");
            return false;
        }
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
     * @param player 玩家
     * @param slot   槽位
     */
    public void onItemTaken(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        try {
            dao.removeHandoverItem(uuid, slot);
            plugin.getLogger().fine("玩家 " + player.getName() + " 从交接容器取出了槽位 " + slot + " 的物品");
        } catch (SQLException e) {
            plugin.getLogger().warning("更新交接容器失败: " + e.getMessage());
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
            return dao.isHandoverContainerEmpty(player.getUniqueId());
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
            dao.destroyHandoverContainer(uuid);
            openContainers.remove(uuid);
            plugin.getLogger().info("玩家 " + player.getName() + " 的交接容器已销毁");
            player.sendMessage(ChatColor.GREEN + "所有物品已领取完毕，交接容器已销毁。");
        } catch (SQLException e) {
            plugin.getLogger().severe("销毁交接容器失败: " + e.getMessage());
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

        // 检查是否为空
        try {
            if (dao.isHandoverContainerEmpty(uuid)) {
                dao.destroyHandoverContainer(uuid);
                plugin.getLogger().info("玩家 " + player.getName() + " 的交接容器已自动销毁（已空）");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("检查交接容器状态失败: " + e.getMessage());
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
            return !dao.isHandoverContainerEmpty(uuid);
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
            Map<Integer, ItemStack> items = dao.loadHandoverItems(uuid);
            return items.size();
        } catch (SQLException e) {
            return 0;
        }
    }
}
