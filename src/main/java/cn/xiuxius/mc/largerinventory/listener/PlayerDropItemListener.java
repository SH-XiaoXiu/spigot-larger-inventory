package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * 物品丢弃事件监听器
 * 阻止玩家丢弃按钮物品
 */
public class PlayerDropItemListener implements Listener {

    private final ButtonManager buttonManager;

    public PlayerDropItemListener(ButtonManager buttonManager) {
        this.buttonManager = buttonManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (buttonManager.isButton(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }
}
