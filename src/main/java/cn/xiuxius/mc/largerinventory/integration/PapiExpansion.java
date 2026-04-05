package cn.xiuxius.mc.largerinventory.integration;

import cn.xiuxius.mc.largerinventory.database.PageNameDAO;
import cn.xiuxius.mc.largerinventory.inventory.PageManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public class PapiExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final PageManager pageManager;
    private final PageNameDAO pageNameDao;

    public PapiExpansion(JavaPlugin plugin, PageManager pageManager, PageNameDAO pageNameDao) {
        this.plugin = plugin;
        this.pageManager = pageManager;
        this.pageNameDao = pageNameDao;
    }

    @Override
    public @NonNull String getIdentifier() {
        return "largerinventory";
    }

    @Override
    public @NonNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NonNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NonNull String params) {
        if (player == null) return "";
        UUID uuid = player.getUniqueId();
        return switch (params) {
            case "current_page" -> String.valueOf(pageManager.getCurrentPage(uuid) + 1);
            case "max_page" -> String.valueOf(pageManager.getMaxPage(uuid) + 1);
            case "effective_max_pages" -> String.valueOf(pageManager.getEffectiveMaxPages(player));
            case "page_name" -> {
                String name = pageManager.getPageName(uuid, pageManager.getCurrentPage(uuid));
                yield name != null ? name : "";
            }
            default -> null;
        };
    }
}
