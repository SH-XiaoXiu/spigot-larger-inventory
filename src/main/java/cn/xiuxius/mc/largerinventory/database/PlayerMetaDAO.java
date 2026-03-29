package cn.xiuxius.mc.largerinventory.database;

import cn.xiuxius.mc.largerinventory.database.model.PlayerMeta;

import java.sql.*;
import java.util.UUID;

/**
 * 玩家元数据访问层，只操作 player_meta 表
 */
public class PlayerMetaDAO {

    private static final int DATA_VERSION = DatabaseManager.DATABASE_VERSION;
    private final DatabaseManager db;

    public PlayerMetaDAO(DatabaseManager db) {
        this.db = db;
    }

    public PlayerMeta getByUUID(UUID uuid) throws SQLException {
        String sql = "SELECT uuid, player_name, current_page, max_page FROM player_meta WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return mapRow(rs);
        }
        return null;
    }

    public PlayerMeta getByName(String playerName) throws SQLException {
        String sql = "SELECT uuid, player_name, current_page, max_page FROM player_meta WHERE LOWER(player_name) = LOWER(?)";
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return mapRow(rs);
        }
        return null;
    }

    /**
     * 玩家加入时调用：首次插入，后续仅更新玩家名和时间戳（不覆盖 current_page / max_page）
     */
    public void upsert(UUID uuid, String playerName) throws SQLException {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO player_meta (uuid, player_name, current_page, max_page, data_version, created_at, updated_at) " +
                "VALUES (?, ?, 0, 0, ?, ?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET player_name = ?, updated_at = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, playerName);
            stmt.setInt(3, DATA_VERSION);
            stmt.setLong(4, now);
            stmt.setLong(5, now);
            stmt.setString(6, playerName);
            stmt.setLong(7, now);
            stmt.executeUpdate();
        }
    }

    /**
     * 更新当前页和最大页（退出 / 定时保存时调用）
     */
    public void update(UUID uuid, int currentPage, int maxPage) throws SQLException {
        String sql = "UPDATE player_meta SET current_page = ?, max_page = ?, data_version = ?, updated_at = ? WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, currentPage);
            stmt.setInt(2, maxPage);
            stmt.setInt(3, DATA_VERSION);
            stmt.setLong(4, System.currentTimeMillis());
            stmt.setString(5, uuid.toString());
            stmt.executeUpdate();
        }
    }

    private PlayerMeta mapRow(ResultSet rs) throws SQLException {
        return new PlayerMeta(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("player_name"),
                rs.getInt("current_page"),
                rs.getInt("max_page")
        );
    }
}
