package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家退出事件监听器
 * 负责保存玩家背包分页数据
 */
public class PlayerQuitListener implements Listener {

    private final PageManager pageManager;

    public PlayerQuitListener(PageManager pageManager) {
        this.pageManager = pageManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // 保存并清理玩家数据
        pageManager.saveAndClearPlayer(player);
    }
}
