package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.config.PluginConfig;
import cn.xiuxius.mc.largerinventory.config.ReloadResult;
import cn.xiuxius.mc.largerinventory.config.Reloadable;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨页拾取监听器
 * <p>
 * 当玩家背包满时，自动将拾取的物品存放到其他页面。
 * 支持拾取音效和物品飞行动画。
 */
public class PlayerPickupItemListener implements Listener, Reloadable {

    // 拾取范围和检查间隔
    private static final double PICKUP_RANGE = 2.0;
    private static final long CHECK_INTERVAL_TICKS = 10L; // 0.5秒检查一次
    private static final long ANIMATION_DELAY_TICKS = 5L; // 动画持续时间
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final PageManager pageManager;
    // 玩家的定时任务
    private final Map<UUID, BukkitTask> playerTasks = new ConcurrentHashMap<>();
    // 正在处理中的物品UUID（防止重复处理）
    private final Set<UUID> processingItems = ConcurrentHashMap.newKeySet();

    public PlayerPickupItemListener(JavaPlugin plugin, ConfigManager configManager, PageManager pageManager, @Deprecated ButtonManager ignored) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.pageManager = pageManager;
    }

    /**
     * 玩家加入时启动检查任务
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!configManager.getConfig().isCrossPagePickup()) return;
        startPickupCheckTask(event.getPlayer());
    }

    /**
     * 玩家退出时停止检查任务
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopPickupCheckTask(event.getPlayer().getUniqueId());
    }

    /**
     * 监听原版拾取事件，用于在拾取后检查附近物品
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (!configManager.getConfig().isCrossPagePickup()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // 拾取后检查是否有附近物品需要处理
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            checkNearbyItems(player);
        }, 1L);
    }

    /**
     * 启动定时检查任务
     */
    private void startPickupCheckTask(Player player) {
        UUID uuid = player.getUniqueId();
        stopPickupCheckTask(uuid);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) {
                checkNearbyItems(p);
            }
        }, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);

        playerTasks.put(uuid, task);
    }

    /**
     * 停止定时检查任务
     */
    private void stopPickupCheckTask(UUID uuid) {
        BukkitTask task = playerTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 检查玩家附近的物品
     */
    private void checkNearbyItems(Player player) {
        if (!player.isOnline()) return;

        // 检查玩家是否正在切换页面
        PageManager.PlayerPageData data = pageManager.getPlayerData(player.getUniqueId());
        if (data == null || data.isSwitching()) return;

        // 获取玩家附近的物品实体
        Collection<Entity> nearbyEntities = player.getNearbyEntities(PICKUP_RANGE, PICKUP_RANGE, PICKUP_RANGE);
        for (Entity entity : nearbyEntities) {
            if (!(entity instanceof Item itemEntity)) continue;
            if (itemEntity.isDead()) continue;

            // 检查物品是否可以被拾取（有拾取延迟）
            if (itemEntity.getPickupDelay() > 0) continue;

            ItemStack itemStack = itemEntity.getItemStack();
            if (itemStack.getType().isAir()) continue;

            // 尝试拾取并跨页存放
            tryPickupCrossPage(player, itemEntity, itemStack);
        }
    }

    /**
     * 尝试拾取物品并进行跨页存放
     */
    private void tryPickupCrossPage(Player player, Item itemEntity, ItemStack pickupItem) {
        UUID itemUUID = itemEntity.getUniqueId();

        // 防止重复处理
        if (!processingItems.add(itemUUID)) {
            return;
        }

        int maxStack = pickupItem.getMaxStackSize();
        PluginConfig cfg = configManager.getConfig();
        int prevSlot = cfg.getPrevButtonSlot();
        int nextSlot = cfg.getNextButtonSlot();

        // 计算当前背包（含快捷栏）能容纳多少
        int canHold = calculateCanHold(player, pickupItem, maxStack, prevSlot, nextSlot);

        // 如果当前背包可以容纳，让原版处理
        if (canHold > 0) {
            processingItems.remove(itemUUID);
            return;
        }

        // 1. 播放拾取音效
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);

        // 2. 物品飞向玩家动画
        Vector direction = player.getEyeLocation().toVector()
                .subtract(itemEntity.getLocation().toVector())
                .normalize();
        itemEntity.setVelocity(direction.multiply(0.5));

        // 3. 禁止物品被其他方式拾取
        itemEntity.setPickupDelay(9999);

        // 4. 触发异步预加载（利用动画时间窗口把目标页面装入缓存）
        pageManager.preloadPagesForPickup(player);

        // 5. 克隆物品数据（延迟后原物品可能已失效）
        ItemStack itemToStore = pickupItem.clone();

        // 6. 延迟后执行存储
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                // 检查物品是否还存在
                if (itemEntity.isDead()) {
                    return;
                }

                // 移除物品实体
                itemEntity.remove();

                // 跨页存放
                int remaining = pageManager.addItemAcrossPages(player, itemToStore);

                // 无法存放的部分丢回地上
                if (remaining > 0) {
                    ItemStack leftover = itemToStore.clone();
                    leftover.setAmount(remaining);
                    player.getWorld().dropItem(player.getLocation(), leftover);
                }
            } finally {
                processingItems.remove(itemUUID);
            }
        }, ANIMATION_DELAY_TICKS);
    }

    /**
     * 计算玩家背包（快捷栏 + 当前页）还能容纳多少指定物品
     */
    private int calculateCanHold(Player player, ItemStack item, int maxStack, int prevSlot, int nextSlot) {
        int canHold = 0;
        Inventory inventory = player.getInventory();

        // 检查快捷栏（0-8）
        for (int slot = 0; slot <= 8; slot++) {
            canHold += calculateSlotCapacity(inventory.getItem(slot), item, maxStack);
        }

        // 检查主背包（9-35），跳过按钮槽
        for (int slot = 9; slot <= 35; slot++) {
            if (slot == prevSlot || slot == nextSlot) continue;
            canHold += calculateSlotCapacity(inventory.getItem(slot), item, maxStack);
        }

        return canHold;
    }

    /**
     * 计算单个槽位能容纳多少指定物品
     */
    private int calculateSlotCapacity(ItemStack existing, ItemStack item, int maxStack) {
        if (existing == null || existing.getType().isAir()) {
            return maxStack;
        }
        if (existing.isSimilar(item)) {
            return maxStack - existing.getAmount();
        }
        return 0;
    }

    @Override
    public ReloadResult onReload(PluginConfig newConfig, JavaPlugin plugin,
                                 Set<Class<? extends Reloadable>> reloaded) {
        if (newConfig.isCrossPagePickup()) {
            // 为尚未启动任务的在线玩家启动
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (!playerTasks.containsKey(player.getUniqueId())) {
                    startPickupCheckTask(player);
                }
            }
        } else {
            // 停止所有任务
            for (UUID uuid : new ArrayList<>(playerTasks.keySet())) {
                stopPickupCheckTask(uuid);
            }
        }
        return ReloadResult.ok();
    }

    /**
     * 清理所有任务
     */
    public void cleanup() {
        for (BukkitTask task : playerTasks.values()) {
            task.cancel();
        }
        playerTasks.clear();
        processingItems.clear();
    }
}
