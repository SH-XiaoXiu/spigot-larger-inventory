package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.handover.HandoverContainerManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 交接容器事件监听器
 * 确保玩家只能取出物品，不能放入物品
 */
public class HandoverContainerListener implements Listener {

    private final JavaPlugin plugin;
    private final HandoverContainerManager containerManager;

    public HandoverContainerListener(JavaPlugin plugin, HandoverContainerManager containerManager) {
        this.plugin = plugin;
        this.containerManager = containerManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory clickedInventory = event.getClickedInventory();
        Inventory topInventory = event.getView().getTopInventory();

        // 检查是否涉及交接容器
        if (!containerManager.isHandoverContainer(topInventory)) {
            return;
        }

        // 如果点击的是交接容器
        if (clickedInventory != null && containerManager.isHandoverContainer(clickedInventory)) {
            // 只允许取出物品（右键或左键取出）
            ItemStack cursor = event.getCursor();
            ItemStack currentItem = event.getCurrentItem();

            // 如果是放置物品（光标有物品且点击空位或物品），取消
            if (cursor != null && !cursor.getType().isAir()) {
                event.setCancelled(true);
                return;
            }

            // 如果是Shift点击交接容器中的物品，允许（取出到玩家背包）
            if (event.isShiftClick() && currentItem != null && !currentItem.getType().isAir()) {
                // 允许Shift取出
                // 注意：需要在事件后更新数据库
                // 这里使用延迟任务来确保物品已被取出
                int slot = event.getRawSlot();
                Bukkit.getScheduler().runTaskLater(plugin, () -> containerManager.onItemTaken(player, slot), 1L);
                return;
            }

            // 如果是取出物品（点击交接容器中的物品，光标为空）
            if (currentItem != null && !currentItem.getType().isAir() && (cursor == null || cursor.getType().isAir())) {
                int slot = event.getRawSlot();
                // 延迟更新数据库
                Bukkit.getScheduler().runTaskLater(plugin, () -> containerManager.onItemTaken(player, slot), 1L);
                return;
            }

            // 其他情况（如数字键交换等），检查是否涉及放入
            // 数字键操作
            if (event.getHotbarButton() != -1) {
                ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
                if (hotbarItem != null && !hotbarItem.getType().isAir()) {
                    // 玩家试图用快捷栏物品交换交接容器物品
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // 如果玩家试图将物品放入交接容器（从其他容器Shift点击）
        if (event.isShiftClick() && clickedInventory != null && !containerManager.isHandoverContainer(clickedInventory)) {
            // 检查目标是否是交接容器
            if (containerManager.isHandoverContainer(topInventory)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Inventory topInventory = event.getView().getTopInventory();
        if (!containerManager.isHandoverContainer(topInventory)) {
            return;
        }

        // 拖拽操作涉及交接容器，检查是否有物品要放入
        if (event.getOldCursor() != null && !event.getOldCursor().getType().isAir()) {
            // 玩家试图拖拽物品到交接容器
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory topInventory = event.getView().getTopInventory();
        if (containerManager.isHandoverContainer(topInventory)) {
            containerManager.onClose(player);
        }
    }
}
