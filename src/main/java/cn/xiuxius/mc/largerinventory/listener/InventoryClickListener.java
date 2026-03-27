package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryClickListener implements Listener {

    private final JavaPlugin plugin;

    // 同一玩家在同一 tick 内只调度一次，防止排序mod触发包洪水
    private final Set<UUID> pendingRestore = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConfigManager configManager;
    private final ButtonManager buttonManager;
    private final PageManager pageManager;

    public InventoryClickListener(JavaPlugin plugin, ConfigManager configManager, ButtonManager buttonManager, PageManager pageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.buttonManager = buttonManager;
        this.pageManager = pageManager;
    }

    /**
     * HIGHEST + ignoreCancelled=false：最后处理，对排序mod有最终决定权。
     * 用 getSlot() 而非 getRawSlot()：在任何容器视图（合成台/箱子）里
     * getSlot() 返回的是玩家背包内的实际槽位号，rawSlot 会因容器偏移而错位。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        boolean clickedPlayerInv = event.getClickedInventory() instanceof PlayerInventory;
        int playerSlot = event.getSlot(); // slot within clickedInventory, not raw view slot
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        boolean buttonsEnabled = pageManager.isButtonsEnabled();

        // 1. 直接点击按钮槽位
        if (buttonsEnabled && clickedPlayerInv && configManager.isButtonSlot(playerSlot)) {
            event.setCancelled(true);
            ItemStack clickedItem = event.getCurrentItem();
            if (buttonManager.isButton(clickedItem)) {
                handleButtonPress(player, buttonManager.getButtonType(clickedItem));
            }
            // 创造模式不发 updateInventory：客户端收到后会认为服务器"给了"物品，
            // 继而发回清除包，形成 setItem→updateInventory→clear→event→... 的闭环
                        return;
        }

        // 2. 数字键热键交换（涉及按钮槽位）
        if (buttonsEnabled && event.getHotbarButton() != -1 && clickedPlayerInv) {
            if (configManager.isButtonSlot(playerSlot) || configManager.isButtonSlot(event.getHotbarButton())) {
                event.setCancelled(true);
                                return;
            }
        }

        // 3. Shift点击按钮物品
        if (buttonsEnabled && event.isShiftClick() && clickedPlayerInv && buttonManager.isButton(event.getCurrentItem())) {
            event.setCancelled(true);
                        return;
        }

        // 4. 双击收集（COLLECT_TO_CURSOR）—— 会把同材质按钮收进光标
        if (buttonsEnabled && event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                ItemStack prevBtn = player.getInventory().getItem(configManager.getPrevButtonSlot());
                ItemStack nextBtn = player.getInventory().getItem(configManager.getNextButtonSlot());
                if ((prevBtn != null && prevBtn.getType() == cursor.getType())
                        || (nextBtn != null && nextBtn.getType() == cursor.getType())) {
                    event.setCancelled(true);
                                        return;
                }
            }
        }

        // 5. 排序模组保护：仅在生存模式下生效（创造模式无排序mod且会引起清除循环）
        if (!creative && clickedPlayerInv) {
            scheduleButtonRestore(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!pageManager.isButtonsEnabled()) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            int slot = rawSlot >= topSize ? rawSlot - topSize : -1;
            if (slot != -1 && configManager.isButtonSlot(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleButtonPress(Player player, String buttonType) {
        if (ButtonManager.BUTTON_PREV.equals(buttonType) && pageManager.canPrevPage(player)) {
            pageManager.prevPage(player);
        } else if (ButtonManager.BUTTON_NEXT.equals(buttonType) && pageManager.canNextPage(player)) {
            pageManager.nextPage(player);
        }
    }

    private void scheduleButtonRestore(Player player) {
        if (pendingRestore.add(player.getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                pendingRestore.remove(player.getUniqueId());
                pageManager.restoreButtons(player);
            });
        }
    }
}
