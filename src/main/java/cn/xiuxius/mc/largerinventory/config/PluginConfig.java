package cn.xiuxius.mc.largerinventory.config;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 配置实体类，所有字段不可变。
 *
 * <p>通过 {@link ConfigManager#getConfig()} 获取当前实例。
 * 调用方不得将其缓存为字段——每次读取配置时重新调用 {@code getConfig()}，
 * 以确保 reload 后自动感知新值。
 */
public final class PluginConfig {

    private final String language;
    private final int prevButtonSlot;
    private final int nextButtonSlot;
    private final Material prevButtonMaterial;
    private final Material nextButtonMaterial;
    private final String prevButtonName;
    private final String nextButtonName;
    private final int maxPages;
    private final int backupRetentionDays;
    private final int autoSaveIntervalSeconds;
    private final boolean crossPagePickup;
    private final boolean crossPageDeathDrop;

    private PluginConfig(String language,
                         int prevButtonSlot, int nextButtonSlot,
                         Material prevButtonMaterial, Material nextButtonMaterial,
                         String prevButtonName, String nextButtonName,
                         int maxPages, int backupRetentionDays, int autoSaveIntervalSeconds,
                         boolean crossPagePickup, boolean crossPageDeathDrop) {
        this.language = language;
        this.prevButtonSlot = prevButtonSlot;
        this.nextButtonSlot = nextButtonSlot;
        this.prevButtonMaterial = prevButtonMaterial;
        this.nextButtonMaterial = nextButtonMaterial;
        this.prevButtonName = prevButtonName;
        this.nextButtonName = nextButtonName;
        this.maxPages = maxPages;
        this.backupRetentionDays = backupRetentionDays;
        this.autoSaveIntervalSeconds = autoSaveIntervalSeconds;
        this.crossPagePickup = crossPagePickup;
        this.crossPageDeathDrop = crossPageDeathDrop;
    }

    /**
     * 从 Bukkit FileConfiguration 构造配置实例。
     */
    public static PluginConfig from(FileConfiguration cfg) {
        return new PluginConfig(
                cfg.getString("language", "zh_CN"),
                cfg.getInt("buttons.prev-page-slot", 27),
                cfg.getInt("buttons.next-page-slot", 35),
                parseMaterial(cfg.getString("buttons.prev-material", "ARROW")),
                parseMaterial(cfg.getString("buttons.next-material", "ARROW")),
                color(cfg.getString("buttons.prev-name", "&6◀ 上一页")),
                color(cfg.getString("buttons.next-name", "&6下一页 ▶")),
                cfg.getInt("limits.max-pages", 0),
                cfg.getInt("data.backup-retention-days", 7),
                cfg.getInt("data.auto-save-interval-seconds", 300),
                cfg.getBoolean("features.cross-page-pickup", true),
                cfg.getBoolean("features.cross-page-death-drop", true)
        );
    }

    private static Material parseMaterial(String name) {
        if (name == null) return Material.ARROW;
        Material m = Material.matchMaterial(name);
        return m != null ? m : Material.ARROW;
    }

    private static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /**
     * 判断槽位是否为按钮槽。
     */
    public boolean isButtonSlot(int slot) {
        return slot == prevButtonSlot || slot == nextButtonSlot;
    }

    public String getLanguage() {
        return language;
    }

    public int getPrevButtonSlot() {
        return prevButtonSlot;
    }

    public int getNextButtonSlot() {
        return nextButtonSlot;
    }

    public Material getPrevButtonMaterial() {
        return prevButtonMaterial;
    }

    public Material getNextButtonMaterial() {
        return nextButtonMaterial;
    }

    public String getPrevButtonName() {
        return prevButtonName;
    }

    public String getNextButtonName() {
        return nextButtonName;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public int getBackupRetentionDays() {
        return backupRetentionDays;
    }

    public int getAutoSaveIntervalSeconds() {
        return autoSaveIntervalSeconds;
    }

    public boolean isCrossPagePickup() {
        return crossPagePickup;
    }

    public boolean isCrossPageDeathDrop() {
        return crossPageDeathDrop;
    }
}
