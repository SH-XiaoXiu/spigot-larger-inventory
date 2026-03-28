package cn.xiuxius.mc.largerinventory.inventory;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 管理创造模式 bypass 状态。
 *
 * bypass 模式下，所有按钮槽拦截均被解除，玩家可自由编辑背包。
 * 仅允许创造模式玩家进入，切换出创造模式时自动强制清除。
 */
public class BypassManager {

    private final Set<UUID> bypassPlayers = new HashSet<>();

    public boolean isInBypass(UUID uuid) {
        return bypassPlayers.contains(uuid);
    }

    /**
     * 进入 bypass：移除按钮槽中的按钮，加入集合。
     */
    public void enterBypass(Player player, ConfigManager configManager) {
        bypassPlayers.add(player.getUniqueId());
        // 清空按钮槽，给玩家空位可用
        player.getInventory().setItem(configManager.getPrevButtonSlot(), null);
        player.getInventory().setItem(configManager.getNextButtonSlot(), null);
        player.updateInventory();
    }

    /**
     * 退出 bypass：检查按钮槽是否为空。
     *
     * @return true 表示退出成功；false 表示按钮槽有物品，需玩家先移走
     */
    public boolean exitBypass(Player player, PageManager pageManager, ConfigManager configManager) {
        var prev = player.getInventory().getItem(configManager.getPrevButtonSlot());
        var next = player.getInventory().getItem(configManager.getNextButtonSlot());
        if ((prev != null && !prev.getType().isAir()) || (next != null && !next.getType().isAir())) {
            return false;
        }
        bypassPlayers.remove(player.getUniqueId());
        pageManager.restoreButtons(player);
        player.updateInventory();
        return true;
    }

    /**
     * 强制清除 bypass（不做槽位检查），用于切换出创造模式时。
     * 不在此处恢复按钮——由 PlayerGameModeChangeListener 的已有逻辑负责。
     */
    public void forceClear(UUID uuid) {
        bypassPlayers.remove(uuid);
    }
}
