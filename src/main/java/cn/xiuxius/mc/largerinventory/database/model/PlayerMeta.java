package cn.xiuxius.mc.largerinventory.database.model;

import java.util.UUID;

/**
 * 玩家元数据
 */
public class PlayerMeta {

    private final UUID uuid;
    private final String playerName;
    private final int currentPage;
    private final int maxPage;

    public PlayerMeta(UUID uuid, String playerName, int currentPage, int maxPage) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.currentPage = currentPage;
        this.maxPage = maxPage;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getMaxPage() {
        return maxPage;
    }
}
