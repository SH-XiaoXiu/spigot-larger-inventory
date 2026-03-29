package cn.xiuxius.mc.largerinventory.database;

import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交接容器访问层，只操作 handover_container 表
 */
public class HandoverDAO {

    private static final int DATA_VERSION = DatabaseManager.DATABASE_VERSION;
    private final DatabaseManager db;

    public HandoverDAO(DatabaseManager db) {
        this.db = db;
    }

    public Map<Integer, ItemStack> load(UUID uuid) throws SQLException {
        Map<Integer, ItemStack> items = new HashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT slot_index, item_data FROM handover_container WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                byte[] data = rs.getBytes("item_data");
                if (data != null) {
                    try {
                        ItemStack item = ItemSerializer.deserialize(data);
                        if (item != null) items.put(rs.getInt("slot_index"), item);
                    } catch (IOException | ClassNotFoundException ignored) {
                    }
                }
            }
        }
        return items;
    }

    /**
     * 追加物品，slot_index 从当前最大值续接，保证 UNIQUE(uuid, slot_index) 不冲突
     */
    public void append(UUID uuid, List<ItemStack> items) throws SQLException {
        int startIndex = nextSlotIndex(uuid);
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO handover_container (uuid, slot_index, item_data, data_version, created_at) " +
                             "VALUES (?, ?, ?, ?, ?)")) {
            int offset = 0;
            for (ItemStack item : items) {
                if (item == null || item.getType().isAir()) continue;
                try {
                    stmt.setString(1, uuid.toString());
                    stmt.setInt(2, startIndex + offset);
                    stmt.setBytes(3, ItemSerializer.serialize(item));
                    stmt.setInt(4, DATA_VERSION);
                    stmt.setLong(5, now);
                    stmt.addBatch();
                    offset++;
                } catch (IOException e) {
                    throw new SQLException("序列化物品失败", e);
                }
            }
            stmt.executeBatch();
        }
    }

    public void remove(UUID uuid, int slotIndex) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM handover_container WHERE uuid = ? AND slot_index = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, slotIndex);
            stmt.executeUpdate();
        }
    }

    public boolean isEmpty(UUID uuid) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM handover_container WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    public void clear(UUID uuid) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM handover_container WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        }
    }

    private int nextSlotIndex(UUID uuid) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COALESCE(MAX(slot_index) + 1, 0) FROM handover_container WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
