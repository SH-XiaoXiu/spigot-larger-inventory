package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.handover.HandoverContainerManager;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import cn.xiuxius.mc.largerinventory.inventory.BypassManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 监听游戏模式切换。
 * <p>
 * 创造模式下插件不干预背包（不恢复按钮、不拦截销毁），
 * 因此切换回非创造模式时需要：
 * 1. 处理按钮槽冲突（创造模式往按钮槽放了物品 → 塞回背包或送交接容器）
 * 2. 重新放置翻页按钮
 */
public class PlayerGameModeChangeListener implements Listener {

    private final JavaPlugin plugin;
    private final PageManager pageManager;
    private final HandoverContainerManager handoverManager;
    private final BypassManager bypassManager;
    private final MessageManager messageManager;

    public PlayerGameModeChangeListener(JavaPlugin plugin, PageManager pageManager,
                                        HandoverContainerManager handoverManager,
                                        BypassManager bypassManager, MessageManager messageManager) {
        this.plugin = plugin;
        this.pageManager = pageManager;
        this.handoverManager = handoverManager;
        this.bypassManager = bypassManager;
        this.messageManager = messageManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) return;
        if (event.getNewGameMode() == GameMode.CREATIVE) return;

        Player player = event.getPlayer();
        // 切换出创造时强制清除 bypass（槽位恢复由后续逻辑负责）
        bypassManager.forceClear(player.getUniqueId());

        // 延迟一 tick：确保游戏模式已实际切换，背包操作在新模式下执行
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            // 统一处理溢出物品（按钮槽冲突 + 页数限制超出）
            int beforeCount = handoverManager.getContainerItemCount(player.getUniqueId());
            pageManager.processOverflow(player);
            int afterCount = handoverManager.getContainerItemCount(player.getUniqueId());
            if (afterCount > beforeCount) {
                player.sendMessage(messageManager.get(MessageKeys.Player.CREATIVE_OVERFLOW, "count", afterCount - beforeCount));
                player.sendMessage(messageManager.get(MessageKeys.Player.RETRIEVE_HINT));
            }
            // 恢复翻页按钮
            pageManager.restoreButtons(player);
        });
    }
}
