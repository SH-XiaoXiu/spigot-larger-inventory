package cn.xiuxius.mc.largerinventory.inventory;

import cn.xiuxius.mc.largerinventory.config.ConfigManager;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 按钮管理器
 * 负责创建和管理翻页按钮
 */
public class ButtonManager {

    // 按钮类型常量
    public static final String BUTTON_PREV = "prev_page";
    public static final String BUTTON_NEXT = "next_page";
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final NamespacedKey buttonKey;

    public ButtonManager(JavaPlugin plugin, ConfigManager configManager, MessageManager messageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.buttonKey = new NamespacedKey(plugin, "largerinventory_button");
    }

    /**
     * 创建上一页按钮
     *
     * @param currentPage 当前页码
     * @param canPrev     是否可以翻到上一页
     * @return 按钮物品
     */
    public ItemStack createPrevButton(int currentPage, boolean canPrev) {
        Material material = canPrev ? configManager.getPrevButtonMaterial() : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            // 设置名称
            String name;
            if (canPrev) {
                name = messageManager.get(MessageKeys.Button.PREV_NAME);
            } else {
                name = messageManager.get(MessageKeys.Button.PREV_FIRST_PAGE);
            }
            meta.setDisplayName(name);

            // 设置描述
            List<String> lore = new ArrayList<>();
            lore.add(messageManager.get(MessageKeys.Button.PREV_LORE_CURRENT, "page", currentPage + 1));
            if (canPrev) {
                lore.add(messageManager.get(MessageKeys.Button.PREV_LORE_CLICK));
            } else {
                lore.add(messageManager.get(MessageKeys.Button.PREV_LORE_CANNOT));
            }
            meta.setLore(lore);

            // 标记为按钮
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(buttonKey, PersistentDataType.STRING, BUTTON_PREV);

            button.setItemMeta(meta);
        }
        return button;
    }

    /**
     * 创建下一页按钮
     *
     * @param currentPage 当前页码
     * @param maxPage     最大页码
     * @param canNext     是否可以翻到下一页
     * @return 按钮物品
     */
    public ItemStack createNextButton(int currentPage, int maxPage, boolean canNext) {
        Material material = canNext ? configManager.getNextButtonMaterial() : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            // 设置名称
            String name;
            if (canNext) {
                name = messageManager.get(MessageKeys.Button.NEXT_NAME);
            } else {
                name = messageManager.get(MessageKeys.Button.NEXT_LAST_PAGE);
            }
            meta.setDisplayName(name);

            // 设置描述
            List<String> lore = new ArrayList<>();
            lore.add(messageManager.get(MessageKeys.Button.NEXT_LORE_CURRENT, "page", currentPage + 1));
            lore.add(messageManager.get(MessageKeys.Button.NEXT_LORE_MAX, "page", maxPage + 1));
            if (canNext) {
                lore.add(messageManager.get(MessageKeys.Button.NEXT_LORE_CLICK));
            } else {
                lore.add(messageManager.get(MessageKeys.Button.NEXT_LORE_CANNOT));
            }
            meta.setLore(lore);

            // 标记为按钮
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(buttonKey, PersistentDataType.STRING, BUTTON_NEXT);

            button.setItemMeta(meta);
        }
        return button;
    }

    /**
     * 检查物品是否为按钮
     *
     * @param item 物品
     * @return 是否为按钮
     */
    public boolean isButton(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.has(buttonKey, PersistentDataType.STRING);
    }

    /**
     * 获取按钮类型
     *
     * @param item 物品
     * @return 按钮类型（BUTTON_PREV/BUTTON_NEXT），如果不是按钮返回null
     */
    public String getButtonType(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.get(buttonKey, PersistentDataType.STRING);
    }

    /**
     * 获取按钮位置的NamespacedKey
     */
    public NamespacedKey getButtonKey() {
        return buttonKey;
    }
}
