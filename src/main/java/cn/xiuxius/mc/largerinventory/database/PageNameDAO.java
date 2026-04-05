package cn.xiuxius.mc.largerinventory.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PageNameDAO {

    private final DatabaseManager db;

    public PageNameDAO(DatabaseManager db) {
        this.db = db;
    }

    public String get(UUID uuid, int page) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT page_name FROM page_names WHERE uuid = ? AND page_number = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("page_name") : null;
        }
    }

    public Map<Integer, String> getAll(UUID uuid) throws SQLException {
        Map<Integer, String> names = new HashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT page_number, page_name FROM page_names WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                names.put(rs.getInt("page_number"), rs.getString("page_name"));
            }
        }
        return names;
    }

    public void set(UUID uuid, int page, String name) throws SQLException {
        String sql = db.isMysql()
                ? "INSERT INTO page_names (uuid, page_number, page_name) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE page_name = VALUES(page_name)"
                : "INSERT OR REPLACE INTO page_names (uuid, page_number, page_name) VALUES (?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    public void delete(UUID uuid, int page) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM page_names WHERE uuid = ? AND page_number = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, page);
            ps.executeUpdate();
        }
    }
}
