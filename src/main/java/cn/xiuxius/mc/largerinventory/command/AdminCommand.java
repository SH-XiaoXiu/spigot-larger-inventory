package cn.xiuxius.mc.largerinventory.command;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.database.PlayerInventoryDAO;
import cn.xiuxius.mc.largerinventory.database.model.PlayerMeta;
import cn.xiuxius.mc.largerinventory.handover.HandoverContainerManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员命令处理器
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final PlayerInventoryDAO dao;
    private final PageManager pageManager; //暂时不用
    private final HandoverContainerManager handoverManager;

    public AdminCommand(JavaPlugin plugin, ConfigManager configManager, PlayerInventoryDAO dao, PageManager pageManager, HandoverContainerManager handoverManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.dao = dao;
        this.pageManager = pageManager;
        this.handoverManager = handoverManager;
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "forcereset" -> handleForceReset(sender);
            case "opencontainer" -> handleOpenContainer(sender, args);
            case "reload" -> handleReload(sender);
            case "info" -> handleInfo(sender, args);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    /**
     * 强制重置所有超出限制的玩家背包
     * 要求：服务器上不能有在线玩家
     */
    private boolean handleForceReset(CommandSender sender) {
        if (!sender.hasPermission("largerinventory.admin.forcereset")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行此命令。");
            return true;
        }

        // 检查是否有在线玩家
        if (Bukkit.getOnlinePlayers().size() > 1 || (Bukkit.getOnlinePlayers().size() == 1 && !(sender instanceof Player))) {
            sender.sendMessage(ChatColor.RED + "服务器上还有其他在线玩家，请先让所有玩家下线后再执行此命令。");
            return true;
        }

        int configMaxPages = configManager.getMaxPages();
        if (configMaxPages <= 0) {
            sender.sendMessage(ChatColor.YELLOW + "当前无页数限制，无需重置。");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "开始重置所有超出限制的玩家背包...");

        // 异步执行重置（全部走数据库）
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int resetCount = 0;
            int totalOverflowItems = 0;

            // 遍历所有离线玩家（从数据库获取）
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                UUID uuid = offlinePlayer.getUniqueId();
                String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : uuid.toString();

                try {
                    PlayerMeta meta = dao.getPlayerMeta(uuid);
                    if (meta == null) {
                        continue;
                    }
                    int maxPage = meta.getMaxPage();

                    // 检查是否超过限制
                    if (maxPage < configMaxPages) {
                        continue;
                    }

                    // 重置该玩家（纯数据库操作）
                    int overflowCount = resetPlayerInventory(uuid, playerName, configMaxPages);
                    if (overflowCount > 0) {
                        totalOverflowItems += overflowCount;
                    }
                    resetCount++;

                } catch (SQLException e) {
                    plugin.getLogger().warning("处理玩家 " + playerName + " 时出错: " + e.getMessage());
                }
            }

            // 在主线程发送结果
            int finalResetCount = resetCount;
            int finalTotalOverflowItems = totalOverflowItems;
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.GREEN + "重置完成！");
                sender.sendMessage(ChatColor.YELLOW + "处理玩家数: " + finalResetCount);
                sender.sendMessage(ChatColor.YELLOW + "超出物品总数: " + finalTotalOverflowItems);
                if (finalTotalOverflowItems > 0) {
                    sender.sendMessage(ChatColor.YELLOW + "玩家可使用 /li opencontainer 取回超出物品。");
                }
            });
        });

        return true;
    }

    /**
     * 重置单个玩家的背包（纯数据库操作，不涉及在线玩家）
     *
     * @return 超出物品数量
     */
    private int resetPlayerInventory(UUID uuid, String playerName, int configMaxPages) throws SQLException {
        PlayerMeta meta = dao.getPlayerMeta(uuid);
        if (meta == null) {
            return 0;
        }
        int maxPage = meta.getMaxPage();

        // 加载所有物品
        Map<Integer, Map<Integer, ItemStack>> allItems = dao.loadAllItems(uuid);

        // 计算限制内的总容量
        int slotsPerPage = 36 - 2;
        int allowedSlots = configMaxPages * slotsPerPage;

        // 收集所有物品
        List<ItemStack> allItemsList = new ArrayList<>();
        for (int page = 0; page <= maxPage; page++) {
            Map<Integer, ItemStack> pageItems = allItems.get(page);
            if (pageItems != null) {
                allItemsList.addAll(pageItems.values());
            }
        }

        // 分离在限制内和超出的物品
        List<ItemStack> keptItems = new ArrayList<>();
        List<ItemStack> overflowItems = new ArrayList<>();

        for (int i = 0; i < allItemsList.size(); i++) {
            if (i < allowedSlots) {
                keptItems.add(allItemsList.get(i));
            } else {
                overflowItems.add(allItemsList.get(i));
            }
        }

        // 重新分配物品到限制内的页
        int prevSlot = configManager.getPrevButtonSlot();
        int nextSlot = configManager.getNextButtonSlot();

        for (int page = 0; page < configMaxPages; page++) {
            Map<Integer, ItemStack> pageItems = new HashMap<>();
            int pageStart = page * slotsPerPage;
            int pageEnd = Math.min(pageStart + slotsPerPage, keptItems.size());

            int slot = 9;
            for (int i = pageStart; i < pageEnd; i++) {
                while (slot == prevSlot || slot == nextSlot) {
                    slot++;
                }
                if (slot > 35) break;
                pageItems.put(slot, keptItems.get(i));
                slot++;
            }

            dao.savePageItems(uuid, page, pageItems);
        }

        // 删除超出页的数据
        dao.deletePagesFrom(uuid, configMaxPages);

        // 更新玩家元数据
        int newMaxPage = Math.min(maxPage, configMaxPages - 1);
        dao.updatePlayerMeta(uuid, 0, newMaxPage);

        // 如果有超出物品，创建交接容器（纯数据库操作）
        if (!overflowItems.isEmpty()) {
            handoverManager.createContainer(uuid, playerName, overflowItems);
        }

        plugin.getLogger().info("已重置玩家 " + playerName + " 的背包");
        return overflowItems.size();
    }

    /**
     * 打开交接容器
     */
    private boolean handleOpenContainer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("largerinventory.admin.opencontainer")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行此命令。");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "此命令只能由玩家执行。");
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "玩家 " + args[1] + " 不在线。");
                return true;
            }
        } else {
            target = player;
        }

        if (!handoverManager.hasContainer(target.getUniqueId())) {
            sender.sendMessage(ChatColor.YELLOW + "玩家 " + target.getName() + " 没有待领取的物品。");
            return true;
        }

        handoverManager.openContainer(target);
        return true;
    }

    /**
     * 重载配置
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("largerinventory.admin.reload")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行此命令。");
            return true;
        }

        configManager.load();
        if (!configManager.validateButtonSlots()) {
            sender.sendMessage(ChatColor.RED + "配置验证失败，请检查按钮位置设置。");
            return true;
        }

        sender.sendMessage(ChatColor.GREEN + "配置已重载。");
        return true;
    }

    /**
     * 查看玩家信息
     */
    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("largerinventory.admin.info")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行此命令。");
            return true;
        }

        UUID uuid;
        String playerName;

        if (args.length >= 2) {
            // 优先尝试获取在线玩家
            Player onlinePlayer = Bukkit.getPlayer(args[1]);
            if (onlinePlayer != null) {
                uuid = onlinePlayer.getUniqueId();
                playerName = onlinePlayer.getName();
            } else {
                // 从数据库查找
                try {
                    PlayerMeta meta = dao.getPlayerMetaByName(args[1]);
                    if (meta == null) {
                        sender.sendMessage(ChatColor.RED + "未找到玩家: " + args[1]);
                        return true;
                    }
                    uuid = meta.getUuid();
                    playerName = meta.getPlayerName() != null ? meta.getPlayerName() : uuid.toString();
                } catch (SQLException e) {
                    sender.sendMessage(ChatColor.RED + "查询玩家失败: " + e.getMessage());
                    return true;
                }
            }
        } else if (sender instanceof Player player) {
            uuid = player.getUniqueId();
            playerName = player.getName();
        } else {
            sender.sendMessage(ChatColor.RED + "请指定玩家: /li info <player>");
            return true;
        }

        try {
            PlayerMeta meta = dao.getPlayerMeta(uuid);
            if (meta == null) {
                sender.sendMessage(ChatColor.RED + "玩家数据不存在: " + playerName);
                return true;
            }
            int currentPage = meta.getCurrentPage();
            int maxPage = meta.getMaxPage();
            int configMaxPages = configManager.getMaxPages();

            sender.sendMessage(ChatColor.GOLD + "===== 玩家背包信息 =====");
            sender.sendMessage(ChatColor.YELLOW + "玩家: " + ChatColor.WHITE + playerName);
            sender.sendMessage(ChatColor.YELLOW + "当前页: " + ChatColor.WHITE + (currentPage + 1));
            sender.sendMessage(ChatColor.YELLOW + "最大页: " + ChatColor.WHITE + (maxPage + 1));
            sender.sendMessage(ChatColor.YELLOW + "配置限制: " + ChatColor.WHITE + (configMaxPages <= 0 ? "无限制" : configMaxPages));

            // 检查交接容器
            int handoverCount = handoverManager.getContainerItemCount(uuid);
            if (handoverCount > 0) {
                sender.sendMessage(ChatColor.YELLOW + "交接容器物品: " + ChatColor.WHITE + handoverCount + " 个");
            }

            // 计算物品数量
            Map<Integer, Map<Integer, ItemStack>> allItems = dao.loadAllItems(uuid);
            int totalItems = 0;
            for (Map<Integer, ItemStack> pageItems : allItems.values()) {
                totalItems += pageItems.size();
            }
            sender.sendMessage(ChatColor.YELLOW + "总物品数: " + ChatColor.WHITE + totalItems);

        } catch (SQLException e) {
            sender.sendMessage(ChatColor.RED + "获取玩家信息失败: " + e.getMessage());
        }

        return true;
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== LargerInventory 管理员帮助 =====");
        sender.sendMessage(ChatColor.YELLOW + "/li forcereset" + ChatColor.WHITE + " - 重置所有超出限制的玩家背包");
        sender.sendMessage(ChatColor.YELLOW + "/li opencontainer [player]" + ChatColor.WHITE + " - 打开交接容器");
        sender.sendMessage(ChatColor.YELLOW + "/li reload" + ChatColor.WHITE + " - 重载配置");
        sender.sendMessage(ChatColor.YELLOW + "/li info [player]" + ChatColor.WHITE + " - 查看玩家背包信息");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("forcereset", "opencontainer", "reload", "info"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("opencontainer") || args[0].equalsIgnoreCase("info")) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .toList());
            }
        }

        String lastArg = args[args.length - 1].toLowerCase();
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(lastArg))
                .collect(Collectors.toList());
    }
}
