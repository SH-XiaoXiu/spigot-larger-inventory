package cn.xiuxius.mc.largerinventory.command;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.config.ReloadCoordinator;
import cn.xiuxius.mc.largerinventory.database.PageItemDAO;
import cn.xiuxius.mc.largerinventory.database.PlayerMetaDAO;
import cn.xiuxius.mc.largerinventory.database.model.PlayerMeta;
import cn.xiuxius.mc.largerinventory.handover.HandoverContainerManager;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import cn.xiuxius.mc.largerinventory.inventory.BypassManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
    private final MessageManager messageManager;
    private final PlayerMetaDAO metaDao;
    private final PageItemDAO pageItemDao;
    private final PageManager pageManager;
    private final HandoverContainerManager handoverManager;
    private final BypassManager bypassManager;
    private final ReloadCoordinator reloadCoordinator;

    public AdminCommand(JavaPlugin plugin, ConfigManager configManager, MessageManager messageManager,
                        PlayerMetaDAO metaDao, PageItemDAO pageItemDao, PageManager pageManager,
                        HandoverContainerManager handoverManager, BypassManager bypassManager,
                        ReloadCoordinator reloadCoordinator) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.metaDao = metaDao;
        this.pageItemDao = pageItemDao;
        this.pageManager = pageManager;
        this.handoverManager = handoverManager;
        this.bypassManager = bypassManager;
        this.reloadCoordinator = reloadCoordinator;
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
            case "bypass" -> handleBypass(sender);
            case "goto" -> handleGoto(sender, args);
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
            sender.sendMessage(messageManager.get(MessageKeys.Command.NO_PERMISSION));
            return true;
        }

        // 检查是否有在线玩家
        if (Bukkit.getOnlinePlayers().size() > 1 || (Bukkit.getOnlinePlayers().size() == 1 && !(sender instanceof Player))) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.FORCERESET_PLAYERS_ONLINE));
            return true;
        }

        int configMaxPages = configManager.getConfig().getMaxPages();
        if (configMaxPages <= 0) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.FORCERESET_NO_LIMIT));
            return true;
        }

        sender.sendMessage(messageManager.get(MessageKeys.Command.FORCERESET_START));

        // 异步执行重置（全部走数据库）
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int resetCount = 0;
            int totalOverflowItems = 0;

            // 遍历所有离线玩家（从数据库获取）
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                UUID uuid = offlinePlayer.getUniqueId();
                String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : uuid.toString();

                try {
                    PlayerMeta meta = metaDao.getByUUID(uuid);
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
                sender.sendMessage(messageManager.get(MessageKeys.Command.FORCERESET_COMPLETE));
                sender.sendMessage(messageManager.get(MessageKeys.Command.FORCERESET_COUNT, "count", finalResetCount));
                sender.sendMessage(messageManager.get(MessageKeys.Command.FORCERESET_OVERFLOW, "count", finalTotalOverflowItems));
                if (finalTotalOverflowItems > 0) {
                    sender.sendMessage(messageManager.get(MessageKeys.Command.FORCERESET_RETRIEVE_HINT));
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
        PlayerMeta meta = metaDao.getByUUID(uuid);
        if (meta == null) {
            return 0;
        }
        int maxPage = meta.getMaxPage();

        List<ItemStack> overflowItems = pageManager.redistributeItems(uuid, configMaxPages, maxPage);

        // 更新玩家元数据
        int newMaxPage = Math.min(maxPage, configMaxPages - 1);
        metaDao.update(uuid, 0, newMaxPage);

        // 如果有超出物品，创建交接容器
        if (!overflowItems.isEmpty()) {
            handoverManager.createContainer(uuid, playerName, overflowItems);
        }

        plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.PLAYER_INVENTORY_RESET, "player", playerName));
        return overflowItems.size();
    }

    /**
     * 打开交接容器
     */
    private boolean handleOpenContainer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("largerinventory.admin.opencontainer")) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.NO_PERMISSION));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.PLAYER_ONLY));
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(messageManager.get(MessageKeys.Command.OPENCONTAINER_PLAYER_NOT_ONLINE, "player", args[1]));
                return true;
            }
        } else {
            target = player;
        }

        if (!handoverManager.hasContainer(target.getUniqueId())) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.OPENCONTAINER_NO_ITEMS, "player", target.getName()));
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
            sender.sendMessage(messageManager.get(MessageKeys.Command.NO_PERMISSION));
            return true;
        }

        if (!configManager.reload()) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.RELOAD_VALIDATION_FAILED));
            return true;
        }

        reloadCoordinator.execute(configManager.getConfig());

        sender.sendMessage(messageManager.get(MessageKeys.Command.RELOAD_SUCCESS));
        return true;
    }

    /**
     * 创造模式 bypass：临时解除按钮槽拦截
     */
    private boolean handleBypass(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.PLAYER_ONLY));
            return true;
        }
        if (!player.hasPermission("largerinventory.admin.bypass")) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.NO_PERMISSION));
            return true;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.BYPASS_CREATIVE_ONLY));
            return true;
        }

        UUID uuid = player.getUniqueId();
        if (bypassManager.isInBypass(uuid)) {
            boolean ok = bypassManager.exitBypass(player, pageManager, configManager);
            if (!ok) {
                player.sendMessage(messageManager.get(MessageKeys.Command.BYPASS_SLOT_NOT_EMPTY));
            } else {
                player.sendMessage(messageManager.get(MessageKeys.Command.BYPASS_DISABLED));
            }
        } else {
            bypassManager.enterBypass(player, configManager);
            player.sendMessage(messageManager.get(MessageKeys.Command.BYPASS_ENABLED));
            player.sendMessage(messageManager.get(MessageKeys.Command.BYPASS_ENABLED_HINT_1));
            player.sendMessage(messageManager.get(MessageKeys.Command.BYPASS_ENABLED_HINT_2));
        }
        return true;
    }

    /**
     * 查看玩家信息
     */
    private boolean handleInfo(CommandSender sender, String[] args) {
        UUID uuid;
        String playerName;

        if (args.length >= 2) {
            if (!sender.hasPermission("largerinventory.admin.info")) {
                sender.sendMessage(messageManager.get(MessageKeys.Command.NO_PERMISSION));
                return true;
            }
            // 优先尝试获取在线玩家
            Player onlinePlayer = Bukkit.getPlayer(args[1]);
            if (onlinePlayer != null) {
                uuid = onlinePlayer.getUniqueId();
                playerName = onlinePlayer.getName();
            } else {
                // 从数据库查找
                try {
                    PlayerMeta meta = metaDao.getByName(args[1]);
                    if (meta == null) {
                        sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_PLAYER_NOT_FOUND, "player", args[1]));
                        return true;
                    }
                    uuid = meta.getUuid();
                    playerName = meta.getPlayerName() != null ? meta.getPlayerName() : uuid.toString();
                } catch (SQLException e) {
                    sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_QUERY_FAILED, "error", e.getMessage()));
                    return true;
                }
            }
        } else if (sender instanceof Player player) {
            if (!sender.hasPermission("largerinventory.player.info")) {
                sender.sendMessage(messageManager.get(MessageKeys.Command.NO_PERMISSION));
                return true;
            }
            uuid = player.getUniqueId();
            playerName = player.getName();
        } else {
            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_SPECIFY_PLAYER));
            return true;
        }

        try {
            PlayerMeta meta = metaDao.getByUUID(uuid);
            if (meta == null) {
                sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_DATA_NOT_FOUND, "player", playerName));
                return true;
            }
            int currentPage = meta.getCurrentPage();
            int maxPage = meta.getMaxPage();
            int configMaxPages = configManager.getConfig().getMaxPages();

            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_TITLE));
            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_PLAYER, "player", playerName));
            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_CURRENT_PAGE, "page", currentPage + 1));
            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_MAX_PAGE, "page", maxPage + 1));
            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_CONFIG_LIMIT, "limit", configMaxPages <= 0 ? "无限制" : String.valueOf(configMaxPages)));
            Player onlineTarget = Bukkit.getPlayer(uuid);
            int effectiveLimit = onlineTarget != null ? pageManager.getEffectiveMaxPages(onlineTarget) : pageManager.getEffectiveMaxPages();
            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_EFFECTIVE_LIMIT, "limit", effectiveLimit));

            // 检查交接容器
            int handoverCount = handoverManager.getContainerItemCount(uuid);
            if (handoverCount > 0) {
                sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_HANDOVER_ITEMS, "count", handoverCount));
            }

            // 计算物品数量
            Map<Integer, Map<Integer, ItemStack>> allItems = pageItemDao.loadAll(uuid);
            int totalItems = 0;
            for (Map<Integer, ItemStack> pageItems : allItems.values()) {
                totalItems += pageItems.size();
            }
            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_TOTAL_ITEMS, "count", totalItems));

        } catch (SQLException e) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.INFO_GET_FAILED, "error", e.getMessage()));
        }

        return true;
    }

    private boolean handleGoto(CommandSender sender, String[] args) {
        if (!sender.hasPermission("largerinventory.player.goto")) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.NO_PERMISSION));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.PLAYER_ONLY));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.GOTO_USAGE));
            return true;
        }
        int page;
        try {
            page = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.GOTO_INVALID_PAGE, "page", args[1]));
            return true;
        }
        int maxPages = pageManager.getEffectiveMaxPages(player);
        if (page < 1 || page > maxPages) {
            sender.sendMessage(messageManager.get(MessageKeys.Command.GOTO_OUT_OF_RANGE, "min", 1, "max", maxPages));
            return true;
        }
        pageManager.switchToPage(player, page - 1);
        sender.sendMessage(messageManager.get(MessageKeys.Command.GOTO_SUCCESS, "page", page));
        return true;
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(messageManager.get(MessageKeys.Command.HELP_TITLE));
        sender.sendMessage(messageManager.get(MessageKeys.Command.HELP_FORCERESET));
        sender.sendMessage(messageManager.get(MessageKeys.Command.HELP_OPENCONTAINER));
        sender.sendMessage(messageManager.get(MessageKeys.Command.HELP_RELOAD));
        sender.sendMessage(messageManager.get(MessageKeys.Command.HELP_INFO));
        sender.sendMessage(messageManager.get(MessageKeys.Command.HELP_BYPASS));
        sender.sendMessage(messageManager.get(MessageKeys.Command.HELP_GOTO));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("forcereset", "opencontainer", "reload", "info", "bypass", "goto"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("goto")) {
                int max = pageManager.getEffectiveMaxPages();
                for (int i = 1; i <= Math.min(max, 20); i++) {
                    completions.add(String.valueOf(i));
                }
            } else if (args[0].equalsIgnoreCase("opencontainer") || args[0].equalsIgnoreCase("info")) {
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
