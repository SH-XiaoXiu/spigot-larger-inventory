package cn.xiuxius.mc.largerinventory.database;

import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 分页物品访问层，只操作 player_inventory 表
 */
public class PageItemDAO {

    private static final int DATA_VERSION = DatabaseManager.DATABASE_VERSION;
    private final DatabaseManager db;

    public PageItemDAO(DatabaseManager db) {
        this.db = db;
    }

    public Map<Integer, ItemStack> loadPage(UUID uuid, int pageNumber) throws SQLException {
        Map<Integer, ItemStack> items = new HashMap<>();
        String sql = "SELECT slot_index, item_data FROM player_inventory WHERE uuid = ? AND page_number = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, pageNumber);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                byte[] data = rs.getBytes("item_data");
                if (data != null) {
                    try {
                        ItemStack item = ItemSerializer.deserialize(data);
                        if (item != null) items.put(rs.getInt("slot_index"), item);
                    } catch (IOException | ClassNotFoundException ignored) {
                        // 损坏数据跳过，不阻塞整页加载
                    }
                }
            }
        }
        return items;
    }

    public Map<Integer, Map<Integer, ItemStack>> loadAll(UUID uuid) throws SQLException {
        Map<Integer, Map<Integer, ItemStack>> result = new HashMap<>();
        String sql = "SELECT page_number, slot_index, item_data FROM player_inventory WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                byte[] data = rs.getBytes("item_data");
                if (data != null) {
                    try {
                        ItemStack item = ItemSerializer.deserialize(data);
                        if (item != null) {
                            result.computeIfAbsent(rs.getInt("page_number"), k -> new HashMap<>())
                                    .put(rs.getInt("slot_index"), item);
                        }
                    } catch (IOException | ClassNotFoundException ignored) {
                    }
                }
            }
        }
        return result;
    }

    public void savePage(UUID uuid, int pageNumber, Map<Integer, ItemStack> items) throws SQLException {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM player_inventory WHERE uuid = ? AND page_number = ?")) {
                    del.setString(1, uuid.toString());
                    del.setInt(2, pageNumber);
                    del.executeUpdate();
                }
                long now = System.currentTimeMillis();
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO player_inventory (uuid, slot_index, page_number, item_data, data_version, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
                        ItemStack item = entry.getValue();
                        if (item == null || item.getType().isAir()) continue;
                        ins.setString(1, uuid.toString());
                        ins.setInt(2, entry.getKey());
                        ins.setInt(3, pageNumber);
                        ins.setBytes(4, ItemSerializer.serialize(item));
                        ins.setInt(5, DATA_VERSION);
                        ins.setLong(6, now);
                        ins.setLong(7, now);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn.commit();
            } catch (IOException | SQLException e) {
                conn.rollback();
                throw (e instanceof SQLException se) ? se : new SQLException("序列化物品失败", e);
            }
        }
    }

    public void deleteFrom(UUID uuid, int fromPage) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM player_inventory WHERE uuid = ? AND page_number >= ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, fromPage);
            stmt.executeUpdate();
        }
    }

    public void clearAll(UUID uuid) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM player_inventory WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        }
    }
}
