package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.config.PluginConfig;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 阻止玩家丢弃正在使用中的翻页按钮。
 *
 * 判断方式：丢弃发生后，检查按钮槽是否因此变空。
 * 若按钮槽已空 → 本次丢弃来自按钮槽 → 取消。
 * 若按钮槽仍有按钮 → 本次丢弃的是跑到其他位置的按钮副本 → 放行（避免卡死）。
 */
public class PlayerDropItemListener implements Listener {

    private final ConfigManager configManager;
    private final ButtonManager buttonManager;

    public PlayerDropItemListener(ConfigManager configManager, ButtonManager buttonManager) {
        this.configManager = configManager;
        this.buttonManager = buttonManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (!buttonManager.isButton(dropped)) return;

        // 检查按钮槽是否因本次丢弃而变空（说明是从按钮槽丢出的）
        PluginConfig cfg = configManager.getConfig();
        ItemStack prev = player.getInventory().getItem(cfg.getPrevButtonSlot());
        ItemStack next = player.getInventory().getItem(cfg.getNextButtonSlot());
        boolean prevMissing = prev == null || prev.getType().isAir() || !buttonManager.isButton(prev);
        boolean nextMissing = next == null || next.getType().isAir() || !buttonManager.isButton(next);

        if (prevMissing || nextMissing) {
            // 按钮槽出现空缺，本次丢弃来自按钮槽，阻止
            event.setCancelled(true);
        }
        // 否则是多余的按钮副本，允许丢弃
    }
}
