package cn.xiuxius.mc.largerinventory.i18n;

import cn.xiuxius.mc.largerinventory.config.PluginConfig;
import cn.xiuxius.mc.largerinventory.config.ReloadResult;
import cn.xiuxius.mc.largerinventory.config.Reloadable;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 国际化消息管理器
 * 负责加载、缓存和格式化多语言消息
 */
public class MessageManager implements Reloadable {

    private final JavaPlugin plugin;
    private final String defaultLocale;
    private String currentLocale;

    // 语言文件缓存
    private FileConfiguration messages;
    private FileConfiguration defaultMessages; // 备用默认语言

    // 前缀缓存
    private String prefix;

    public MessageManager(JavaPlugin plugin, String locale) {
        this.plugin = plugin;
        this.defaultLocale = "zh_CN";
        this.currentLocale = locale != null ? locale : defaultLocale;
    }

    /**
     * 加载语言文件
     */
    public void load() {
        // 确保语言文件夹存在
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        // 保存默认语言文件（如果不存在）
        saveDefaultLangFile("zh_CN");
        saveDefaultLangFile("en_US");

        // 加载当前语言文件
        loadLocaleFile(currentLocale);

        // 加载默认语言文件作为备用
        if (!currentLocale.equals(defaultLocale)) {
            loadDefaultLocaleFile();
        }

        // 缓存前缀
        this.prefix = get("prefix", "&6[LargerInventory]");
    }

    private void saveDefaultLangFile(String locale) {
        String fileName = "lang/" + locale + ".yml";
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }
    }

    private void loadLocaleFile(String locale) {
        File file = new File(plugin.getDataFolder(), "lang/" + locale + ".yml");
        if (file.exists()) {
            messages = YamlConfiguration.loadConfiguration(file);
        } else {
            // 尝试从jar中加载
            InputStream is = plugin.getResource("lang/" + locale + ".yml");
            if (is != null) {
                messages = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(is, StandardCharsets.UTF_8));
            }
        }
    }

    private void loadDefaultLocaleFile() {
        File file = new File(plugin.getDataFolder(), "lang/" + defaultLocale + ".yml");
        if (file.exists()) {
            defaultMessages = YamlConfiguration.loadConfiguration(file);
        }
    }

    /**
     * 获取当前语言
     */
    public String getLocale() {
        return currentLocale;
    }

    /**
     * 切换语言
     */
    public void setLocale(String locale) {
        this.currentLocale = locale;
        loadLocaleFile(locale);
        if (!locale.equals(defaultLocale)) {
            loadDefaultLocaleFile();
        }
    }

    /**
     * 获取消息（带占位符替换）
     *
     * @param key  消息键
     * @param args 占位符参数（键值对形式）
     */
    public String get(String key, Object... args) {
        String message = messages != null ? messages.getString(key) : null;

        // 回退到默认语言
        if (message == null && defaultMessages != null) {
            message = defaultMessages.getString(key);
        }

        // 回退到键名
        if (message == null) {
            message = key;
        }

        // 替换占位符 {key} -> value
        if (args != null && args.length >= 2) {
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 < args.length) {
                    String placeholder = "{" + args[i] + "}";
                    String value = String.valueOf(args[i + 1]);
                    message = message.replace(placeholder, value);
                }
            }
        }

        // 转换颜色代码
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * 获取消息（带默认值）
     */
    public String get(String key, String defaultValue) {
        String message = messages != null ? messages.getString(key) : null;

        // 回退到默认语言
        if (message == null && defaultMessages != null) {
            message = defaultMessages.getString(key);
        }

        if (message == null) {
            message = defaultValue;
        }

        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * 获取带前缀的消息
     */
    public String getWithPrefix(String key, Object... args) {
        return prefix + " " + get(key, args);
    }

    /**
     * 获取日志消息（不带颜色代码）
     */
    public String getLog(String key, Object... args) {
        String message = get(key, args);
        // 移除颜色代码用于日志
        return ChatColor.stripColor(message);
    }

    /**
     * 重新加载语言文件
     */
    public void reload() {
        load();
    }

    @Override
    public ReloadResult onReload(PluginConfig newConfig, JavaPlugin plugin,
                                 Set<Class<? extends Reloadable>> reloaded) {
        setLocale(newConfig.getLanguage());
        reload();
        return ReloadResult.ok();
    }

    /**
     * 获取前缀
     */
    public String getPrefix() {
        return prefix;
    }
}
