package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.handover.HandoverContainerManager;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 玩家加入事件监听器
 * 负责初始化玩家背包分页数据
 */
public class PlayerJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final PageManager pageManager;
    private final HandoverContainerManager handoverManager;
    private final MessageManager messageManager;

    public PlayerJoinListener(JavaPlugin plugin, ConfigManager configManager,
                              PageManager pageManager, HandoverContainerManager handoverManager,
                              MessageManager messageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.pageManager = pageManager;
        this.handoverManager = handoverManager;
        this.messageManager = messageManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 初始化玩家数据
        pageManager.initPlayer(player);
        // 统一处理溢出物品（按钮槽冲突 + 页数限制超出）
        pageManager.processOverflow(player);

        // 检查是否有交接容器待领取
        if (handoverManager.hasContainer(player.getUniqueId())) {
            int count = handoverManager.getContainerItemCount(player.getUniqueId());
            player.sendMessage(messageManager.get(MessageKeys.Player.ITEMS_PENDING, "count", count));
            player.sendMessage(messageManager.get(MessageKeys.Player.RETRIEVE_HINT));
        }

        // 推送资源包
        String url = configManager.getConfig().getResourcePackUrl();
        if (url != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.setResourcePack(url, null,
                        configManager.getConfig().getResourcePackPrompt(),
                        configManager.getConfig().isResourcePackRequired());
            }, 20L);
        }
    }
}
