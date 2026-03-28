package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.GameMode;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨页拾取监听器
 * <p>
 * 由于 Minecraft 原版在背包满时不会触发拾取事件，
 * 我们需要主动检测玩家附近的物品并尝试跨页存放。
 * <p>
 * 工作流程：
 * 当玩家拾取物品成功后，启动定时检查
 * 定时检查玩家附近的可拾取物品
 * 如果当前背包满了，尝试跨页存放
 */
public class PlayerPickupItemListener implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final PageManager pageManager;
    private final ButtonManager buttonManager;

    // 玩家的定时任务
    private final Map<UUID, BukkitTask> playerTasks = new ConcurrentHashMap<>();
    // 正在处理的物品（防止多玩家同时拾取导致复制）
    private final Set<UUID> processingItems = ConcurrentHashMap.newKeySet();
    // 拾取范围和检查间隔
    private static final double PICKUP_RANGE = 2.0;
    private static final long CHECK_INTERVAL_TICKS = 10L; // 0.5秒检查一次

    public PlayerPickupItemListener(JavaPlugin plugin, ConfigManager configManager, PageManager pageManager, ButtonManager buttonManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.pageManager = pageManager;
        this.buttonManager = buttonManager;
    }

    /**
     * 玩家加入时启动检查任务
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!configManager.isCrossPagePickup()) return;
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
     * 监听原版拾取事件，用于触发后续检查
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (!configManager.isCrossPagePickup()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // 拾取后检查是否有附近物品需要处理
        // 延迟 1 tick 后检查，确保拾取逻辑完成
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            checkNearbyItems(player);
        }, 1L);
    }

    /**
     * 启动定时检查任务
     */
    private void startPickupCheckTask(Player player) {
        UUID uuid = player.getUniqueId();

        // 如果已有任务，先停止
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

        GameMode gameMode = player.getGameMode();
        if (gameMode != GameMode.SURVIVAL && gameMode != GameMode.ADVENTURE) return;

        // 检查玩家是否正在切换页面
        PageManager.PlayerPageData data = pageManager.getPlayerData(player.getUniqueId());
        if (data == null || data.switching) return;

        // 获取玩家附近的物品实体
        Collection<Entity> nearbyEntities = player.getNearbyEntities(PICKUP_RANGE, PICKUP_RANGE, PICKUP_RANGE);
        for (Entity entity : nearbyEntities) {
            if (!(entity instanceof Item itemEntity)) continue;
            if (itemEntity.isDead()) continue;

            // 检查物品是否可以被拾取（有拾取延迟）
            if (itemEntity.getPickupDelay() > 0) continue;

            ItemStack itemStack = itemEntity.getItemStack();
            if (itemStack == null || itemStack.getType().isAir()) continue;

            // 尝试拾取并跨页存放
            tryPickupCrossPage(player, itemEntity, itemStack);
        }
    }

    /**
     * 尝试拾取物品并进行跨页存放
     *
     * @return 是否成功拾取
     */
    private boolean tryPickupCrossPage(Player player, Item itemEntity, ItemStack pickupItem) {
        UUID itemUUID = itemEntity.getUniqueId();

        // 原子性检查：使用 add() 返回值判断是否成功"锁定"该物品
        // add() 返回 true 表示之前不存在，成功添加
        // add() 返回 false 表示已存在，其他玩家正在处理
        if (!processingItems.add(itemUUID)) {
            return false; // 其他玩家正在处理这个物品
        }

        try {
            // 再次检查物品是否还有效（可能在等待锁的过程中被其他方式拾取）
            if (itemEntity.isDead()) {
                return false;
            }

            int totalAmount = pickupItem.getAmount();
            int maxStack = pickupItem.getMaxStackSize();
            int prevSlot = configManager.getPrevButtonSlot();
            int nextSlot = configManager.getNextButtonSlot();

            // 计算当前背包（含快捷栏）能容纳多少
            int canHold = calculateCanHold(player, pickupItem, maxStack, prevSlot, nextSlot);

            // 如果当前背包完全无法容纳，检查是否可以跨页存放
            if (canHold == 0) {
                // 记录物品类型（在处理之前）
                var itemType = pickupItem.getType();

                // 可以跨页存放，执行拾取
                itemEntity.remove();

                // 跨页存放
                int remaining = pageManager.addItemAcrossPages(player, pickupItem);

                int stored = totalAmount - remaining;
                // 跨页拾取成功，物品已存入其他页面

                if (remaining > 0) {
                    // 无法存放的部分放回地上
                    ItemStack leftover = pickupItem.clone();
                    leftover.setAmount(remaining);
                    player.getWorld().dropItem(player.getLocation(), leftover);
                }

                return true;
            }

            // 当前背包可以部分容纳，让原版处理
            return false;
        } finally {
            // 无论成功与否，都要释放锁
            processingItems.remove(itemUUID);
        }
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
