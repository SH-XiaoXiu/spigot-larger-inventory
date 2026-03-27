package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.handover.HandoverContainerManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 玩家加入事件监听器
 * 负责初始化玩家背包分页数据
 */
public class PlayerJoinListener implements Listener {

    private final PageManager pageManager;
    private final HandoverContainerManager handoverManager;

    public PlayerJoinListener(PageManager pageManager, HandoverContainerManager handoverManager) {
        this.pageManager = pageManager;
        this.handoverManager = handoverManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 初始化玩家数据
        pageManager.initPlayer(player);
        // 处理按钮位置冲突，无处安放的物品送交接容器
        List<ItemStack> unplaceable = pageManager.handleButtonSlotConflict(player);
        if (!unplaceable.isEmpty()) {
            handoverManager.createContainer(player, unplaceable);
        }

        // 检查是否有交接容器待领取
        if (handoverManager.hasContainer(player.getUniqueId())) {
            int count = handoverManager.getContainerItemCount(player.getUniqueId());
            player.sendMessage(ChatColor.GOLD + "你有 " + count + " 个物品待领取！");
            player.sendMessage(ChatColor.YELLOW + "使用 /li opencontainer 打开交接容器取回物品。");
        }
    }
}
