package cn.xiuxius.mc.largerinventory.config;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 配置管理器
 * 负责加载、验证和提供配置项
 */
public class ConfigManager {

    private final JavaPlugin plugin;
    private int prevButtonSlot;
    private int nextButtonSlot;
    private Material prevButtonMaterial;
    private Material nextButtonMaterial;
    private String prevButtonName;
    private String nextButtonName;
    private int maxPages;
    private int backupRetentionDays;
    private int autoSaveIntervalSeconds;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 加载配置
     */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        FileConfiguration config = plugin.getConfig();

        // 按钮位置
        prevButtonSlot = config.getInt("buttons.prev-page-slot", 27);
        nextButtonSlot = config.getInt("buttons.next-page-slot", 35);

        // 按钮外观
        prevButtonMaterial = Material.matchMaterial(config.getString("buttons.prev-material", "ARROW"));
        nextButtonMaterial = Material.matchMaterial(config.getString("buttons.next-material", "ARROW"));
        prevButtonName = ChatColor.translateAlternateColorCodes('&', config.getString("buttons.prev-name", "&6◀ 上一页"));
        nextButtonName = ChatColor.translateAlternateColorCodes('&', config.getString("buttons.next-name", "&6下一页 ▶"));

        // 分页限制
        maxPages = config.getInt("limits.max-pages", 0);

        // 数据保护
        backupRetentionDays = config.getInt("data.backup-retention-days", 7);
        autoSaveIntervalSeconds = config.getInt("data.auto-save-interval-seconds", 300);
    }

    /**
     * 验证按钮槽位配置是否有效
     *
     * @return 验证结果
     */
    public boolean validateButtonSlots() {
        // 必须在背包范围内(0-35)
        // 不能在快捷栏(0-8)
        // 两个按钮不能相同
        if (prevButtonSlot < 9 || prevButtonSlot > 35) {
            plugin.getLogger().warning("上一页按钮位置无效: " + prevButtonSlot + "，必须在9-35范围内");
            return false;
        }
        if (nextButtonSlot < 9 || nextButtonSlot > 35) {
            plugin.getLogger().warning("下一页按钮位置无效: " + nextButtonSlot + "，必须在9-35范围内");
            return false;
        }
        if (prevButtonSlot == nextButtonSlot) {
            plugin.getLogger().warning("两个按钮位置不能相同: " + prevButtonSlot);
            return false;
        }
        return true;
    }

    /**
     * 获取上一页按钮槽位
     */
    public int getPrevButtonSlot() {
        return prevButtonSlot;
    }

    /**
     * 获取下一页按钮槽位
     */
    public int getNextButtonSlot() {
        return nextButtonSlot;
    }

    /**
     * 获取上一页按钮材质
     */
    public Material getPrevButtonMaterial() {
        return prevButtonMaterial != null ? prevButtonMaterial : Material.ARROW;
    }

    /**
     * 获取下一页按钮材质
     */
    public Material getNextButtonMaterial() {
        return nextButtonMaterial != null ? nextButtonMaterial : Material.ARROW;
    }

    /**
     * 获取上一页按钮名称
     */
    public String getPrevButtonName() {
        return prevButtonName;
    }

    /**
     * 获取下一页按钮名称
     */
    public String getNextButtonName() {
        return nextButtonName;
    }

    /**
     * 获取最大页数限制
     *
     * @return 0表示无限制
     */
    public int getMaxPages() {
        return maxPages;
    }

    /**
     * 获取备份保留天数
     */
    public int getBackupRetentionDays() {
        return backupRetentionDays;
    }

    /**
     * 获取自动保存间隔（秒）
     */
    public int getAutoSaveIntervalSeconds() {
        return autoSaveIntervalSeconds;
    }

    /**
     * 检查槽位是否为按钮位置
     *
     * @param slot 槽位索引
     * @return 是否为按钮位置
     */
    public boolean isButtonSlot(int slot) {
        return slot == prevButtonSlot || slot == nextButtonSlot;
    }
}
