package cn.xiuxius.mc;

import cn.xiuxius.mc.largerinventory.command.AdminCommand;
import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.database.DatabaseManager;
import cn.xiuxius.mc.largerinventory.database.PlayerInventoryDAO;
import cn.xiuxius.mc.largerinventory.listener.HandoverContainerListener;
import cn.xiuxius.mc.largerinventory.handover.HandoverContainerManager;
import cn.xiuxius.mc.largerinventory.inventory.BypassManager;
import cn.xiuxius.mc.largerinventory.inventory.ButtonManager;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import cn.xiuxius.mc.largerinventory.listener.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class LargerInventory extends JavaPlugin {

    // 组件
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PlayerInventoryDAO playerInventoryDAO;
    private ButtonManager buttonManager;
    private PageManager pageManager;
    private HandoverContainerManager handoverContainerManager;
    private BypassManager bypassManager;

    // 定时任务
    private BukkitTask autoSaveTask;

    @Override
    public void onEnable() {
        // 初始化配置
        configManager = new ConfigManager(this);
        configManager.load();

        // 验证配置
        if (!configManager.validateButtonSlots()) {
            getLogger().severe("配置验证失败，插件将禁用！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 初始化数据库
        databaseManager = new DatabaseManager(this);
        databaseManager.init();

        // 初始化数据访问层
        playerInventoryDAO = new PlayerInventoryDAO(databaseManager);

        // 初始化按钮管理器
        buttonManager = new ButtonManager(this, configManager);

        // 初始化分页管理器
        pageManager = new PageManager(this, configManager, buttonManager, playerInventoryDAO);

        // 初始化交接容器管理器
        handoverContainerManager = new HandoverContainerManager(this, playerInventoryDAO);

        // 初始化 bypass 管理器
        bypassManager = new BypassManager();

        // 注册事件监听器
        registerListeners();

        // 注册命令
        registerCommands();

        // 启动定时任务
        startScheduledTasks();

        // 初始化在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            pageManager.initPlayer(player);
            pageManager.handleButtonSlotConflict(player);
        }

        getLogger().info("LargerInventory 插件已启用！");
        getLogger().info("按钮位置: 上一页=" + configManager.getPrevButtonSlot() + ", 下一页=" + configManager.getNextButtonSlot());
        getLogger().info("最大页数: " + (configManager.getMaxPages() <= 0 ? "无限制" : configManager.getMaxPages()));
    }

    @Override
    public void onDisable() {
        // 停止定时任务
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        // 保存所有在线玩家数据
        for (Player player : Bukkit.getOnlinePlayers()) {
            pageManager.saveAndClearPlayer(player);
        }

        // 关闭数据库
        databaseManager.close();

        getLogger().info("LargerInventory 插件已禁用！");
    }

    /**
     * 注册事件监听器
     */
    private void registerListeners() {
        // 背包点击监听器（核心）
        getServer().getPluginManager().registerEvents(
                new InventoryClickListener(this, configManager, buttonManager, pageManager, bypassManager), this);

        // 拖拽监听器（已合并到InventoryClickListener中）
        // 丢弃监听器
        getServer().getPluginManager().registerEvents(
                new PlayerDropItemListener(configManager, buttonManager), this);

        // 玩家加入/退出监听器
        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(pageManager, handoverContainerManager), this);
        getServer().getPluginManager().registerEvents(
                new PlayerQuitListener(pageManager), this);

        // 交接容器监听器
        getServer().getPluginManager().registerEvents(
                new HandoverContainerListener(this, handoverContainerManager), this);

        // 游戏模式切换监听器（离开创造模式时修复按钮槽）
        getServer().getPluginManager().registerEvents(
                new PlayerGameModeChangeListener(this, pageManager, handoverContainerManager, bypassManager), this);
    }

    /**
     * 注册命令
     */
    private void registerCommands() {
        AdminCommand adminCommand = new AdminCommand(this, configManager, playerInventoryDAO, pageManager, handoverContainerManager, bypassManager);
        getCommand("largerinventory").setExecutor(adminCommand);
        getCommand("largerinventory").setTabCompleter(adminCommand);
    }

    /**
     * 启动定时任务
     */
    private void startScheduledTasks() {
        int autoSaveInterval = configManager.getAutoSaveIntervalSeconds();

        // 定时刷脏任务（主线程快照 + 内部异步写 DB，实现写入聚合）
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            pageManager.flushAllDirtyPages();
            getLogger().fine("脏页刷写完成");
        }, autoSaveInterval * 20L, autoSaveInterval * 20L);

        getLogger().info("定时任务已启动：自动保存间隔 " + autoSaveInterval + " 秒");
    }

    /**
     * 重载配置
     */
    public void reloadPluginConfig() {
        configManager.load();
        if (!configManager.validateButtonSlots()) {
            getLogger().severe("配置验证失败！");
        }
    }

    // Getters
    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PlayerInventoryDAO getPlayerInventoryDAO() {
        return playerInventoryDAO;
    }

    public ButtonManager getButtonManager() {
        return buttonManager;
    }

    public PageManager getPageManager() {
        return pageManager;
    }

    public HandoverContainerManager getHandoverContainerManager() {
        return handoverContainerManager;
    }
}
