package cn.xiuxius.mc.largerinventory.config;

import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 配置管理器：负责加载、校验、热更新配置文件，维护当前 {@link PluginConfig} 实例。
 *
 * <h3>热更新规范</h3>
 * 业务代码持有 {@code ConfigManager} 引用，访问配置时调用 {@link #getConfig()} 取得最新实例，
 * 严禁将 {@code PluginConfig} 缓存为字段。
 */
public class ConfigManager {

    private final JavaPlugin plugin;
    private MessageManager messageManager;
    private PluginConfig config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setMessageManager(MessageManager messageManager) {
        this.messageManager = messageManager;
    }

    /**
     * 启动时调用：保存默认配置并解析。不执行校验。
     */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = PluginConfig.from(plugin.getConfig());
    }

    /**
     * 热更新：从磁盘重新读取并校验配置。
     * 若新配置有效则原子替换当前实例，返回 {@code true}；
     * 若无效则保留旧配置，返回 {@code false}。
     */
    public boolean reload() {
        plugin.reloadConfig();
        PluginConfig candidate = PluginConfig.from(plugin.getConfig());
        if (!validate(candidate)) {
            return false;
        }
        config = candidate;
        return true;
    }

    /**
     * 校验当前配置，无效时记录警告日志。
     *
     * @return 配置有效返回 {@code true}
     */
    public boolean validate() {
        return validate(config);
    }

    private boolean validate(PluginConfig cfg) {
        if (cfg.getPrevButtonSlot() < 9 || cfg.getPrevButtonSlot() > 35) {
            logWarning(MessageKeys.Log.CONFIG_BUTTON_SLOT_INVALID, "button", "prev", "slot", cfg.getPrevButtonSlot());
            return false;
        }
        if (cfg.getNextButtonSlot() < 9 || cfg.getNextButtonSlot() > 35) {
            logWarning(MessageKeys.Log.CONFIG_BUTTON_SLOT_INVALID, "button", "next", "slot", cfg.getNextButtonSlot());
            return false;
        }
        if (cfg.getPrevButtonSlot() == cfg.getNextButtonSlot()) {
            logWarning(MessageKeys.Log.CONFIG_BUTTON_SLOTS_SAME, "slot", cfg.getPrevButtonSlot());
            return false;
        }
        if (cfg.isMysql()) {
            if (cfg.getMysqlHost() == null || cfg.getMysqlHost().isEmpty()) {
                logWarning(MessageKeys.Log.DB_MYSQL_CONFIG_INVALID, "error", "host is empty");
                return false;
            }
            if (cfg.getMysqlDatabase() == null || cfg.getMysqlDatabase().isEmpty()) {
                logWarning(MessageKeys.Log.DB_MYSQL_CONFIG_INVALID, "error", "database is empty");
                return false;
            }
            if (cfg.getMysqlPoolSize() < 1 || cfg.getMysqlPoolSize() > 50) {
                logWarning(MessageKeys.Log.DB_MYSQL_CONFIG_INVALID, "error", "pool-size must be 1-50");
                return false;
            }
        }
        return true;
    }

    private void logWarning(String key, Object... args) {
        if (messageManager != null) {
            plugin.getLogger().warning(messageManager.getLog(key, args));
        } else {
            plugin.getLogger().warning(key);
        }
    }

    /**
     * 返回当前配置实例。
     * 调用方每次均应重新调用此方法，不得将返回值缓存为字段。
     */
    public PluginConfig getConfig() {
        return config;
    }
}
