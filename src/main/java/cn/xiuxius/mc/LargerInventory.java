package cn.xiuxius.mc;

import cn.xiuxius.mc.largerinventory.command.AdminCommand;
import cn.xiuxius.mc.largerinventory.config.*;
import cn.xiuxius.mc.largerinventory.database.DatabaseManager;
import cn.xiuxius.mc.largerinventory.database.BackupDAO;
import cn.xiuxius.mc.largerinventory.database.HandoverDAO;
import cn.xiuxius.mc.largerinventory.database.PageItemDAO;
import cn.xiuxius.mc.largerinventory.database.PageNameDAO;
import cn.xiuxius.mc.largerinventory.database.PlayerMetaDAO;
import cn.xiuxius.mc.largerinventory.handover.HandoverContainerManager;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.BypassManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import cn.xiuxius.mc.largerinventory.listener.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;

public final class LargerInventory extends JavaPlugin implements Reloadable {

    // 组件
    private ConfigManager configManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private PlayerMetaDAO playerMetaDAO;
    private PageItemDAO pageItemDAO;
    private HandoverDAO handoverDAO;
    private PageNameDAO pageNameDAO;
    private BackupDAO backupDAO;
    private ButtonManager buttonManager;
    private PageManager pageManager;
    private HandoverContainerManager handoverContainerManager;
    private BypassManager bypassManager;

    // 定时任务
    private BukkitTask autoSaveTask;

    // 热更新调度器
    private ReloadCoordinator reloadCoordinator;

    // 监听器
    private PlayerPickupItemListener pickupItemListener;

    @Override
    public void onEnable() {
        // 打印版本信息
        getLogger().info("Version: " + getDescription().getVersion());

        // 初始化配置
        configManager = new ConfigManager(this);
        configManager.load();

        // 验证配置
        if (!configManager.validate()) {
            getLogger().severe("Configuration validation failed, Plugin disabled.");
            getLogger().severe("(配置验证失败，插件已禁用)");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 初始化消息管理器
        messageManager = new MessageManager(this, configManager.getConfig().getLanguage());
        messageManager.load();
        configManager.setMessageManager(messageManager);

        // 初始化数据库
        databaseManager = new DatabaseManager(this, messageManager);
        databaseManager.init();

        // 初始化数据访问层
        playerMetaDAO = new PlayerMetaDAO(databaseManager);
        pageItemDAO = new PageItemDAO(databaseManager);
        handoverDAO = new HandoverDAO(databaseManager);
        pageNameDAO = new PageNameDAO(databaseManager);
        backupDAO = new BackupDAO(databaseManager);

        // 初始化按钮管理器
        buttonManager = new ButtonManager(this, configManager, messageManager);

        // 初始化分页管理器
        pageManager = new PageManager(this, configManager, buttonManager, messageManager, playerMetaDAO, pageItemDAO, pageNameDAO);

        // 初始化交接容器管理器
        handoverContainerManager = new HandoverContainerManager(this, messageManager, handoverDAO);

        // 注册溢出物品处理器
        pageManager.setOverflowHandler((player, items) -> handoverContainerManager.createContainer(player, items));

        // 初始化 bypass 管理器
        bypassManager = new BypassManager();

        // 注册事件监听器
        registerListeners();

        // 初始化热更新调度器（在监听器注册后，pickupItemListener 已就绪）
        reloadCoordinator = new ReloadCoordinator(this);
        reloadCoordinator.register(messageManager);
        reloadCoordinator.register(pickupItemListener);
        reloadCoordinator.register(pageManager);
        reloadCoordinator.register(this);

        // 注册命令
        registerCommands();

        // 启动定时任务
        startScheduledTasks();

        // 初始化在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            pageManager.initPlayer(player);
            pageManager.processOverflow(player);
        }

        // PlaceholderAPI 集成
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new cn.xiuxius.mc.largerinventory.integration.PapiExpansion(this, pageManager, pageNameDAO).register();
            getLogger().info(messageManager.getLog(MessageKeys.Log.PAPI_REGISTERED));
        }

        getLogger().info(messageManager.getLog(MessageKeys.Log.PLUGIN_ENABLED));
        getLogger().info(messageManager.getLog(MessageKeys.Log.BUTTON_POSITIONS,
                "prev", configManager.getConfig().getPrevButtonSlot(),
                "next", configManager.getConfig().getNextButtonSlot()));
        int maxPagesCfg = configManager.getConfig().getMaxPages();
        String maxPagesMsg = maxPagesCfg <= 0 ? String.valueOf(PageManager.MAX_PAGES_HARD_LIMIT) : String.valueOf(maxPagesCfg);
        getLogger().info(messageManager.getLog(MessageKeys.Log.MAX_PAGES, "limit", maxPagesMsg));
    }

    @Override
    public void onDisable() {
        // 停止定时任务
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }

        // 清理拾取监听器的资源
        if (pickupItemListener != null) {
            pickupItemListener.cleanup();
        }

        // 保存所有在线玩家数据
        for (Player player : Bukkit.getOnlinePlayers()) {
            pageManager.saveAndClearPlayer(player);
        }

        // 关闭数据库
        databaseManager.close();

        getLogger().info(messageManager.getLog(MessageKeys.Log.PLUGIN_DISABLED));
    }

    /**
     * 注册事件监听器
     */
    private void registerListeners() {
        // 背包点击监听器（核心）
        getServer().getPluginManager().registerEvents(
                new InventoryClickListener(configManager, buttonManager, pageManager, bypassManager), this);

        // 拖拽监听器（已合并到InventoryClickListener中）
        // 丢弃监听器
        getServer().getPluginManager().registerEvents(
                new PlayerDropItemListener(configManager, buttonManager), this);

        // 玩家加入/退出监听器
        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(pageManager, handoverContainerManager, messageManager), this);
        getServer().getPluginManager().registerEvents(
                new PlayerQuitListener(pageManager), this);

        // 交接容器监听器
        getServer().getPluginManager().registerEvents(
                new HandoverContainerListener(this, handoverContainerManager), this);

        // 游戏模式切换监听器（离开创造模式时修复按钮槽）
        getServer().getPluginManager().registerEvents(
                new PlayerGameModeChangeListener(this, pageManager, handoverContainerManager, bypassManager, messageManager), this);

        // 跨页拾取监听器
        pickupItemListener = new PlayerPickupItemListener(this, configManager, pageManager, messageManager);
        getServer().getPluginManager().registerEvents(pickupItemListener, this);

        // 跨页死亡掉落监听器
        getServer().getPluginManager().registerEvents(
                new PlayerDeathListener(configManager, pageManager, buttonManager), this);

        // 按钮物品消耗保护（弓射箭、食用、右键使用等）
        getServer().getPluginManager().registerEvents(
                new PlayerItemUseListener(configManager, buttonManager, pageManager, this), this);
    }

    /**
     * 注册命令
     */
    private void registerCommands() {
        AdminCommand adminCommand = new AdminCommand(this, configManager, messageManager, playerMetaDAO, pageItemDAO, pageManager, handoverContainerManager, bypassManager, reloadCoordinator, backupDAO);
        getCommand("largerinventory").setExecutor(adminCommand);
        getCommand("largerinventory").setTabCompleter(adminCommand);
    }

    /**
     * 启动定时任务
     */
    private void startScheduledTasks() {
        int autoSaveInterval = configManager.getConfig().getAutoSaveIntervalSeconds();

        // 定时刷脏任务（主线程快照 + 内部异步写 DB，实现写入聚合）
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            pageManager.flushAllDirtyPages();
        }, autoSaveInterval * 20L, autoSaveInterval * 20L);

        getLogger().info(messageManager.getLog(MessageKeys.Log.SCHEDULED_TASK_STARTED, "interval", autoSaveInterval));

        // 备份清理任务（每24小时）
        int retentionDays = configManager.getConfig().getBackupRetentionDays();
        if (retentionDays > 0) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    int cleaned = backupDAO.cleanupOldBackups(retentionDays);
                    if (cleaned > 0) {
                        getLogger().info(messageManager.getLog(MessageKeys.Log.BACKUP_CLEANUP, "count", cleaned));
                    }
                } catch (java.sql.SQLException e) {
                    getLogger().warning(messageManager.getLog(MessageKeys.Log.BACKUP_CLEANUP_FAILED, "error", e.getMessage()));
                }
            }, 20 * 60 * 60L, 20 * 60 * 60L * 24);
        }
    }

    @Override
    public ReloadResult onReload(PluginConfig newConfig, JavaPlugin plugin,
                                 Set<Class<? extends Reloadable>> reloaded) {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        int interval = newConfig.getAutoSaveIntervalSeconds();
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(
                this, pageManager::flushAllDirtyPages, interval * 20L, interval * 20L);
        return ReloadResult.ok();
    }

    public ReloadCoordinator getReloadCoordinator() {
        return reloadCoordinator;
    }

}
