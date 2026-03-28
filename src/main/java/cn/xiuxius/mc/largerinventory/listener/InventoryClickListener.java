package cn.xiuxius.mc.largerinventory.listener;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.inventory.BypassManager;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class InventoryClickListener implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final ButtonManager buttonManager;
    private final PageManager pageManager;
    private final BypassManager bypassManager;

    // 当前 tick 内玩家触发按钮槽事件的计数（用于多包检测）
    private final Map<UUID, Integer> tickPacketCount = new HashMap<>();
    // 玩家挂起的翻页任务 ID（多包时会被取消）
    private final Map<UUID, Integer> pendingTasks = new HashMap<>();
    // 翻页冷却集合：翻页后 2 tick 内不再允许重复翻页，防止跨 tick 迟到包二次触发
    private final Set<UUID> pageTurnCooldown = new HashSet<>();

    public InventoryClickListener(JavaPlugin plugin, ConfigManager configManager,
                                  ButtonManager buttonManager, PageManager pageManager,
                                  BypassManager bypassManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.buttonManager = buttonManager;
        this.pageManager = pageManager;
        this.bypassManager = bypassManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!pageManager.isButtonsEnabled()) return;

        boolean isPlayerInv = event.getClickedInventory() instanceof PlayerInventory;
        int slot = event.getSlot();

        // 1. 数字键热键交换：只要涉及按钮槽，无论 bypass 均拦截
        //    （防止快捷键把按钮换走，或把物品换进按钮槽造成数据损坏）
        if (event.getHotbarButton() != -1 && isPlayerInv) {
            if (configManager.isButtonSlot(slot) || configManager.isButtonSlot(event.getHotbarButton())) {
                event.setCancelled(true);
                return;
            }
        }

        // 2. 非按钮槽，跳过；但 COLLECT_TO_CURSOR 需额外检查
        if (!isPlayerInv || !configManager.isButtonSlot(slot)) {
            if (pageManager.isButtonsEnabled() && event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir()) {
                    ItemStack prevBtn = player.getInventory().getItem(configManager.getPrevButtonSlot());
                    ItemStack nextBtn = player.getInventory().getItem(configManager.getNextButtonSlot());
                    if ((prevBtn != null && prevBtn.getType() == cursor.getType())
                            || (nextBtn != null && nextBtn.getType() == cursor.getType())) {
                        event.setCancelled(true);
                    }
                }
            }
            return;
        }

        // --- 以下均为按钮槽事件 ---

        // 3. bypass 模式：放行所有操作
        if (bypassManager.isInBypass(player.getUniqueId())) {
            return;
        }

        // 4. 取消所有对按钮槽的操作
        event.setCancelled(true);

        // 5. DROP / CONTROL_DROP：只同步客户端，不翻页
        ClickType click = event.getClick();
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            player.updateInventory();
            return;
        }

        // 6. 多包检测：记录本 tick 内的按钮槽事件数
        UUID uuid = player.getUniqueId();
        int count = tickPacketCount.getOrDefault(uuid, 0) + 1;
        tickPacketCount.put(uuid, count);

        if (count > 1) {
            // 同一 tick 内第 2+ 个事件：判定为批量操作（整理模组、快速操作等）
            // 取消挂起的翻页任务，不翻页；延迟 3 tick 清理计数，覆盖突发包的尾部
            Integer pendingId = pendingTasks.remove(uuid);
            if (pendingId != null) {
                Bukkit.getScheduler().cancelTask(pendingId);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> tickPacketCount.remove(uuid), 3L);
            player.updateInventory();
            return;
        }

        // 7. 左键单击：调度延迟翻页任务（delay=0，在下一 tick 执行）
        //    若本 tick 内又来第 2 个包，上面的逻辑会取消该任务
        if (click == ClickType.LEFT || click == ClickType.CREATIVE) {
            ItemStack item = player.getInventory().getItem(slot);
            if (buttonManager.isButton(item)) {
                String type = buttonManager.getButtonType(item);
                int taskId = Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingTasks.remove(uuid);
                    tickPacketCount.remove(uuid); // 翻页时清理计数，此后才允许新一轮计数
                    if (!player.isOnline()) return;
                    // 冷却检查：2 tick 内不允许重复翻页，防止跨 tick 迟到包二次触发
                    if (pageTurnCooldown.contains(uuid)) return;
                    pageTurnCooldown.add(uuid);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> pageTurnCooldown.remove(uuid), 2L);
                    handleButtonPress(player, type);
                    player.updateInventory();
                }).getTaskId();
                pendingTasks.put(uuid, taskId);
                // 不在此处另起 cleanup task；由翻页任务本身负责清理 count
                player.updateInventory();
                return;
            }
        }

        // 非左键（右键等）：下一 tick 清理计数
        Bukkit.getScheduler().runTask(plugin, () -> tickPacketCount.remove(uuid));

        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!pageManager.isButtonsEnabled()) return;
        if (bypassManager.isInBypass(player.getUniqueId())) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            int s = rawSlot >= topSize ? rawSlot - topSize : -1;
            if (s != -1 && configManager.isButtonSlot(s)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleButtonPress(Player player, String buttonType) {
        if (ButtonManager.BUTTON_PREV.equals(buttonType) && pageManager.canPrevPage(player)) {
            pageManager.prevPage(player);
        } else if (ButtonManager.BUTTON_NEXT.equals(buttonType) && pageManager.canNextPage(player)) {
            pageManager.nextPage(player);
        }
    }
}
