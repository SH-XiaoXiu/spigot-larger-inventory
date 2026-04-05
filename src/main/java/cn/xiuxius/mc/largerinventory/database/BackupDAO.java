package cn.xiuxius.mc.largerinventory.database;

import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class BackupDAO {

    private final DatabaseManager db;

    public BackupDAO(DatabaseManager db) {
        this.db = db;
    }

    /**
     * 创建备份：从 player_inventory 复制到 inventory_backups
     */
    public void createBackup(UUID uuid, String backupName, String description) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO backup_meta (backup_name, uuid, created_at, description) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, backupName);
                    ps.setString(2, uuid.toString());
                    ps.setLong(3, now);
                    ps.setString(4, description);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO inventory_backups (backup_name, uuid, page_number, slot_index, item_data, created_at) " +
                                "SELECT ?, uuid, page_number, slot_index, item_data, ? FROM player_inventory WHERE uuid = ?")) {
                    ps.setString(1, backupName);
                    ps.setLong(2, now);
                    ps.setString(3, uuid.toString());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * 加载备份数据
     */
    public Map<Integer, Map<Integer, ItemStack>> loadBackup(UUID uuid, String backupName) throws SQLException {
        Map<Integer, Map<Integer, ItemStack>> result = new HashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT page_number, slot_index, item_data FROM inventory_backups WHERE backup_name = ? AND uuid = ?")) {
            ps.setString(1, backupName);
            ps.setString(2, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int page = rs.getInt("page_number");
                int slot = rs.getInt("slot_index");
                byte[] data = rs.getBytes("item_data");
                if (data == null) continue;
                try {
                    ItemStack item = ItemSerializer.deserialize(data);
                    result.computeIfAbsent(page, k -> new HashMap<>()).put(slot, item);
                } catch (IOException | ClassNotFoundException ignored) {}
            }
        }
        return result;
    }

    /**
     * 列出玩家的所有备份
     */
    public List<BackupInfo> listBackups(UUID uuid) throws SQLException {
        List<BackupInfo> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT backup_name, created_at, description FROM backup_meta WHERE uuid = ? ORDER BY created_at DESC")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new BackupInfo(
                        rs.getString("backup_name"),
                        rs.getLong("created_at"),
                        rs.getString("description")
                ));
            }
        }
        return list;
    }

    /**
     * 删除指定备份
     */
    public void deleteBackup(UUID uuid, String backupName) throws SQLException {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM inventory_backups WHERE backup_name = ? AND uuid = ?")) {
                    ps.setString(1, backupName);
                    ps.setString(2, uuid.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM backup_meta WHERE backup_name = ? AND uuid = ?")) {
                    ps.setString(1, backupName);
                    ps.setString(2, uuid.toString());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * 清理过期备份
     *
     * @return 清理的备份数量
     */
    public int cleanupOldBackups(int retentionDays) throws SQLException {
        long threshold = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000;
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int count;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM inventory_backups WHERE backup_name IN " +
                                "(SELECT backup_name FROM backup_meta WHERE created_at < ?)")) {
                    ps.setLong(1, threshold);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM backup_meta WHERE created_at < ?")) {
                    ps.setLong(1, threshold);
                    count = ps.executeUpdate();
                }
                conn.commit();
                return count;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * 检查备份是否存在
     */
    public boolean exists(UUID uuid, String backupName) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM backup_meta WHERE backup_name = ? AND uuid = ?")) {
            ps.setString(1, backupName);
            ps.setString(2, uuid.toString());
            return ps.executeQuery().next();
        }
    }

    public record BackupInfo(String name, long createdAt, String description) {}
}
