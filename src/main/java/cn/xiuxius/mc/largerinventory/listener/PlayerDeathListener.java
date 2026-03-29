package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跨页死亡掉落监听器
 * <p>
 * 当游戏规则 keepInventory = false 时，让所有页面的物品都掉落
 * 当前页物品由原版 Minecraft 处理，其他页物品由插件在死亡位置生成掉落
 */
public class PlayerDeathListener implements Listener {

    private final ConfigManager configManager;
    private final PageManager pageManager;
    private final ButtonManager buttonManager;

    public PlayerDeathListener(ConfigManager configManager, PageManager pageManager, ButtonManager buttonManager) {
        this.configManager = configManager;
        this.pageManager = pageManager;
        this.buttonManager = buttonManager;
    }

    /**
     * 玩家死亡事件
     * 注意：此事件在 PlayerDeathEvent 之后触发
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        // 检查功能是否启用
        if (!configManager.getConfig().isCrossPageDeathDrop()) {
            return;
        }

        Player player = event.getEntity();
        World world = player.getWorld();
        UUID uuid = player.getUniqueId();

        // 检查游戏规则：如果保持物品，则无需处理
        if (Boolean.TRUE.equals(world.getGameRuleValue(GameRule.KEEP_INVENTORY))) {
            return;
        }

        // 获取所有页面物品
        Map<Integer, Map<Integer, ItemStack>> allPageItems = pageManager.getAllPageItems(player);

        // 获取当前页
        int currentPage = pageManager.getCurrentPage(uuid);

        // 掉落其他页面的物品（当前页由原版处理）
        Location deathLocation = player.getLocation();
        for (Map.Entry<Integer, Map<Integer, ItemStack>> pageEntry : allPageItems.entrySet()) {
            int pageNum = pageEntry.getKey();
            if (pageNum == currentPage) {
                continue; // 当前页由原版处理
            }

            Map<Integer, ItemStack> pageItems = pageEntry.getValue();
            for (ItemStack item : pageItems.values()) {
                if (item != null && !item.getType().isAir() && !buttonManager.isButton(item)) {
                    dropItemNaturally(deathLocation, item);
                }
            }
        }

        // 清空所有页面数据（当前页已由原版清空）
        pageManager.clearAllPages(player);
    }

    /**
     * 玩家重生事件
     * 确保玩家重生后背包状态正确
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // 重置按钮状态（clearAllPages 已经重置了页码）
        pageManager.restoreButtons(player);
    }

    /**
     * 在指定位置自然掉落物品
     */
    private void dropItemNaturally(Location location, ItemStack item) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        Item dropped = location.getWorld().dropItemNaturally(location, item);
        dropped.setPickupDelay(10); // 设置拾取延迟，防止立即被拾取
    }
}
