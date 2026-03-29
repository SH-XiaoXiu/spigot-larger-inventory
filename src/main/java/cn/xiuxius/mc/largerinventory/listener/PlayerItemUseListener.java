package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.config.PluginConfig;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 阻止按钮物品被游戏机制消耗。
 * <p>
 * 当按钮材料是可消耗物品（如箭、食物、末影珍珠等）时，
 * 玩家通过弓射箭、食用、投掷等方式会自动消耗按钮。
 * 此监听器拦截这些事件，防止按钮被消耗。
 */
public class PlayerItemUseListener implements Listener {

    private final ConfigManager configManager;
    private final ButtonManager buttonManager;
    private final PageManager pageManager;
    private final JavaPlugin plugin;

    public PlayerItemUseListener(ConfigManager configManager, ButtonManager buttonManager,
                                 PageManager pageManager, JavaPlugin plugin) {
        this.configManager = configManager;
        this.buttonManager = buttonManager;
        this.pageManager = pageManager;
        this.plugin = plugin;
    }

    /**
     * 拦截弓/弩发射时消耗按钮物品作为弹药。
     * <p>
     * setCancelled(true) 在部分服务端实现中不会阻止物品消耗，
     * 因此在下一 tick 通过 restoreButtons 恢复按钮。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack consumable = event.getConsumable();
        if (!buttonManager.isButton(consumable)) return;

        event.setCancelled(true);

        // 部分服务端 cancel 后仍会消耗物品，下一 tick 恢复
        plugin.getServer().getScheduler().runTask(plugin,
                () -> pageManager.restoreButtons(player));
    }

    /**
     * 拦截食用/饮用消耗按钮物品。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (buttonManager.isButton(event.getItem())) {
            event.setCancelled(true);
            // 防御性恢复
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> pageManager.restoreButtons(player));
        }
    }

    /**
     * 拦截右键使用按钮物品。
     * <p>
     * 1. 按钮在手中被右键使用（投掷末影珍珠、雪球等）→ 直接 cancel
     * 2. 玩家右键弓/弩时，按钮材料是箭类且背包无非按钮弹药 → cancel（阻止拉弓动画）
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // 1. 按钮在手中被右键使用
        if (item != null && buttonManager.isButton(item)) {
            event.setCancelled(true);
            return;
        }

        // 2. 弓/弩拉弓时，检查是否只有按钮箭可用
        if (item == null) return;
        Material handMaterial = item.getType();
        if (handMaterial != Material.BOW && handMaterial != Material.CROSSBOW) return;

        PluginConfig cfg = configManager.getConfig();
        Material prevMat = cfg.getPrevButtonMaterial();
        Material nextMat = cfg.getNextButtonMaterial();

        boolean prevIsAmmo = isBowAmmo(prevMat);
        boolean nextIsAmmo = isBowAmmo(nextMat);

        if (!prevIsAmmo && !nextIsAmmo) return;

        // 检查背包中是否有非按钮的同类型弹药
        if (prevIsAmmo && hasNonButtonAmmo(player, prevMat)) return;
        if (nextIsAmmo && hasNonButtonAmmo(player, nextMat)) return;

        // 无非按钮弹药可用，阻止拉弓动画
        event.setCancelled(true);
    }

    /**
     * 判断材料是否可作为弓/弩的弹药。
     */
    private boolean isBowAmmo(Material material) {
        return material == Material.ARROW
                || material == Material.SPECTRAL_ARROW
                || material == Material.TIPPED_ARROW
                || material == Material.FIREWORK_ROCKET;
    }

    /**
     * 检查玩家背包中是否有非按钮的指定材料物品。
     */
    private boolean hasNonButtonAmmo(Player player, Material ammoType) {
        for (ItemStack invItem : player.getInventory()) {
            if (invItem != null && invItem.getType() == ammoType
                    && !buttonManager.isButton(invItem)) {
                return true;
            }
        }
        return false;
    }
}
