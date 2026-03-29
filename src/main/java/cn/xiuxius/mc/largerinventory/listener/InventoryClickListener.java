package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.config.PluginConfig;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.BypassManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 背包点击事件监听器。
 *
 * <p>职责：识别按钮槽相关操作并拦截，将翻页请求委托给 {@link PageManager}。
 * 多包防重与翻页冷却等业务规则由 PageManager 内部处理。
 */
public class InventoryClickListener implements Listener {

    private final ConfigManager configManager;
    private final ButtonManager buttonManager;
    private final PageManager pageManager;
    private final BypassManager bypassManager;

    public InventoryClickListener(ConfigManager configManager,
                                  ButtonManager buttonManager, PageManager pageManager,
                                  BypassManager bypassManager) {
        this.configManager = configManager;
        this.buttonManager = buttonManager;
        this.pageManager = pageManager;
        this.bypassManager = bypassManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!pageManager.isButtonsEnabled()) return;

        boolean isPlayerInv = event.getClickedInventory() instanceof PlayerInventory;
        int slot = event.getSlot();
        PluginConfig cfg = configManager.getConfig();

        // 1. 数字键热键交换：涉及按钮槽一律拦截，防止快捷键破坏按钮槽数据
        if (event.getHotbarButton() != -1 && isPlayerInv) {
            if (cfg.isButtonSlot(slot) || cfg.isButtonSlot(event.getHotbarButton())) {
                event.setCancelled(true);
                return;
            }
        }

        // 2. 非按钮槽：只额外检查 COLLECT_TO_CURSOR 是否会把按钮吸走
        if (!isPlayerInv || !cfg.isButtonSlot(slot)) {
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir()) {
                    ItemStack prevBtn = player.getInventory().getItem(cfg.getPrevButtonSlot());
                    ItemStack nextBtn = player.getInventory().getItem(cfg.getNextButtonSlot());
                    if ((prevBtn != null && prevBtn.getType() == cursor.getType())
                            || (nextBtn != null && nextBtn.getType() == cursor.getType())) {
                        event.setCancelled(true);
                    }
                }
            }
            return;
        }

        // --- 以下均为按钮槽事件 ---

        // 3. bypass 模式：放行所有操作
        if (bypassManager.isInBypass(player.getUniqueId())) return;

        // 4. 取消所有对按钮槽的操作
        event.setCancelled(true);

        // 5. DROP / CONTROL_DROP：只同步客户端，不翻页
        ClickType click = event.getClick();
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            player.updateInventory();
            return;
        }

        // 6. 左键 / 创造模式点击：委托 PageManager 处理翻页（含防重与冷却）
        if (click == ClickType.LEFT || click == ClickType.CREATIVE) {
            ItemStack item = player.getInventory().getItem(slot);
            if (buttonManager.isButton(item)) {
                pageManager.onButtonClick(player, buttonManager.getButtonType(item));
                player.updateInventory();
                return;
            }
        }

        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!pageManager.isButtonsEnabled()) return;
        if (bypassManager.isInBypass(player.getUniqueId())) return;

        PluginConfig cfg = configManager.getConfig();
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            int s = rawSlot >= topSize ? rawSlot - topSize : -1;
            if (s != -1 && cfg.isButtonSlot(s)) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
