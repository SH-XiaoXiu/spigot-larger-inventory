package cn.xiuxius.mc.largerinventory.database;

import cn.xiuxius.mc.largerinventory.database.model.PlayerMeta;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 * 玩家背包数据访问层
 * 负责物品的序列化/反序列化和数据库CRUD操作
 */
public class PlayerInventoryDAO {

    /**
     * 当前数据版本
     */
    private static final int DATA_VERSION = DatabaseManager.DATABASE_VERSION;
    private final DatabaseManager databaseManager;

    public PlayerInventoryDAO(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 序列化ItemStack为字节数组
     */
    public byte[] serializeItem(ItemStack item) throws IOException {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeObject(item);
            return baos.toByteArray();
        }
    }

    /**
     * 反序列化字节数组为ItemStack
     */
    public ItemStack deserializeItem(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            Object obj = bois.readObject();
            return (ItemStack) obj;
        }
    }

    // 玩家元数据操作

    /**
     * 获取或创建玩家元数据
     */
    public PlayerMeta getPlayerMeta(UUID uuid) throws SQLException {
        String sql = "SELECT uuid, player_name, current_page, max_page FROM player_meta WHERE uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new PlayerMeta(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("player_name"),
                        rs.getInt("current_page"),
                        rs.getInt("max_page")
                );
            }
        }
        // 不存在则创建默认记录
        return null;
    }

    /**
     * 根据玩家名查找玩家元数据
     */
    public PlayerMeta getPlayerMetaByName(String playerName) throws SQLException {
        String sql = "SELECT uuid, player_name, current_page, max_page FROM player_meta WHERE LOWER(player_name) = LOWER(?)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new PlayerMeta(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("player_name"),
                        rs.getInt("current_page"),
                        rs.getInt("max_page")
                );
            }
        }
        return null;
    }

    /**
     * 创建或更新玩家元数据记录
     * 每次玩家加入时调用，自动更新玩家名称（支持改名）
     */
    public void createOrUpdatePlayerMeta(UUID uuid, String playerName) throws SQLException {
        long now = System.currentTimeMillis();
        // 使用 INSERT ... ON CONFLICT 语法，更新玩家名和时间戳
        String sql = "INSERT INTO player_meta (uuid, player_name, current_page, max_page, data_version, created_at, updated_at) " +
                "VALUES (?, ?, 0, 0, ?, ?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET player_name = ?, updated_at = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, playerName);
            stmt.setInt(3, DATA_VERSION);
            stmt.setLong(4, now);
            stmt.setLong(5, now);
            // ON CONFLICT UPDATE 部分
            stmt.setString(6, playerName);
            stmt.setLong(7, now);
            stmt.executeUpdate();
        }
    }


    /**
     * 更新玩家元数据
     */
    public void updatePlayerMeta(UUID uuid, int currentPage, int maxPage) throws SQLException {
        String sql = "UPDATE player_meta SET current_page = ?, max_page = ?, data_version = ?, updated_at = ? WHERE uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, currentPage);
            stmt.setInt(2, maxPage);
            stmt.setInt(3, DATA_VERSION);
            stmt.setLong(4, System.currentTimeMillis());
            stmt.setString(5, uuid.toString());
            stmt.executeUpdate();
        }
    }

    // 玩家物品操作

    /**
     * 保存单页物品
     */
    public void savePageItems(UUID uuid, int pageNumber, Map<Integer, ItemStack> items) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String deleteSql = "DELETE FROM player_inventory WHERE uuid = ? AND page_number = ?";
                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                    deleteStmt.setString(1, uuid.toString());
                    deleteStmt.setInt(2, pageNumber);
                    deleteStmt.executeUpdate();
                }

                long now = System.currentTimeMillis();
                String insertSql = "INSERT INTO player_inventory (uuid, slot_index, page_number, item_data, data_version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
                        ItemStack item = entry.getValue();
                        if (item != null && !item.getType().isAir()) {
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.setInt(2, entry.getKey());
                            insertStmt.setInt(3, pageNumber);
                            insertStmt.setBytes(4, serializeItem(item));
                            insertStmt.setInt(5, DATA_VERSION);
                            insertStmt.setLong(6, now);
                            insertStmt.setLong(7, now);
                            insertStmt.addBatch();
                        }
                    }
                    insertStmt.executeBatch();
                }
                conn.commit();
            } catch (IOException | SQLException e) {
                conn.rollback();
                throw (e instanceof SQLException se) ? se : new SQLException("序列化物品失败", e);
            }
        }
    }

    /**
     * 加载单页物品
     */
    public Map<Integer, ItemStack> loadPageItems(UUID uuid, int pageNumber) throws SQLException {
        Map<Integer, ItemStack> items = new HashMap<>();
        String sql = "SELECT slot_index, item_data FROM player_inventory WHERE uuid = ? AND page_number = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, pageNumber);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                int slot = rs.getInt("slot_index");
                byte[] data = rs.getBytes("item_data");
                if (data != null) {
                    try {
                        ItemStack item = deserializeItem(data);
                        if (item != null) {
                            items.put(slot, item);
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        // 记录错误但继续加载其他物品
                    }
                }
            }
        }
        return items;
    }


    /**
     * 获取玩家所有物品（按页分组）
     */
    public Map<Integer, Map<Integer, ItemStack>> loadAllItems(UUID uuid) throws SQLException {
        Map<Integer, Map<Integer, ItemStack>> allItems = new HashMap<>();
        String sql = "SELECT page_number, slot_index, item_data FROM player_inventory WHERE uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                int page = rs.getInt("page_number");
                int slot = rs.getInt("slot_index");
                byte[] data = rs.getBytes("item_data");
                if (data != null) {
                    try {
                        ItemStack item = deserializeItem(data);
                        if (item != null) {
                            allItems.computeIfAbsent(page, k -> new HashMap<>()).put(slot, item);
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        // 记录错误但继续加载其他物品
                    }
                }
            }
        }
        return allItems;
    }

    /**
     * 删除指定页及之后的所有物品
     */
    public void deletePagesFrom(UUID uuid, int fromPage) throws SQLException {
        String sql = "DELETE FROM player_inventory WHERE uuid = ? AND page_number >= ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, fromPage);
            stmt.executeUpdate();
        }
    }

    // 交接容器操作

    /**
     * 创建交接容器物品
     * 新物品追加到现有物品之后，避免重复调用时的唯一约束冲突
     */
    public void createHandoverItems(UUID uuid, List<ItemStack> items) throws SQLException {
        long now = System.currentTimeMillis();

        // 查询当前最大 slot_index，新物品从其后追加
        int startIndex = 0;
        String maxSql = "SELECT COALESCE(MAX(slot_index) + 1, 0) FROM handover_container WHERE uuid = ?";
        try (Connection maxConn = databaseManager.getConnection();
             PreparedStatement maxStmt = maxConn.prepareStatement(maxSql)) {
            maxStmt.setString(1, uuid.toString());
            ResultSet rs = maxStmt.executeQuery();
            if (rs.next()) {
                startIndex = rs.getInt(1);
            }
        }

        String sql = "INSERT INTO handover_container (uuid, slot_index, item_data, data_version, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int offset = 0;
            for (int i = 0; i < items.size(); i++) {
                ItemStack item = items.get(i);
                if (item != null && !item.getType().isAir()) {
                    stmt.setString(1, uuid.toString());
                    stmt.setInt(2, startIndex + offset);
                    stmt.setBytes(3, serializeItem(item));
                    stmt.setInt(4, DATA_VERSION);
                    stmt.setLong(5, now);
                    stmt.addBatch();
                    offset++;
                }
            }
            stmt.executeBatch();
        } catch (IOException e) {
            throw new SQLException("序列化物品失败", e);
        }
    }

    /**
     * 加载交接容器物品
     */
    public Map<Integer, ItemStack> loadHandoverItems(UUID uuid) throws SQLException {
        Map<Integer, ItemStack> items = new HashMap<>();
        String sql = "SELECT slot_index, item_data FROM handover_container WHERE uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                int slot = rs.getInt("slot_index");
                byte[] data = rs.getBytes("item_data");
                if (data != null) {
                    try {
                        ItemStack item = deserializeItem(data);
                        if (item != null) {
                            items.put(slot, item);
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        // 记录错误但继续加载其他物品
                    }
                }
            }
        }
        return items;
    }

    /**
     * 删除交接容器中的单个物品
     */
    public void removeHandoverItem(UUID uuid, int slotIndex) throws SQLException {
        String sql = "DELETE FROM handover_container WHERE uuid = ? AND slot_index = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, slotIndex);
            stmt.executeUpdate();
        }
    }

    /**
     * 检查交接容器是否为空
     */
    public boolean isHandoverContainerEmpty(UUID uuid) throws SQLException {
        String sql = "SELECT COUNT(*) FROM handover_container WHERE uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) == 0;
            }
        }
        return true;
    }

    /**
     * 销毁交接容器
     */
    public void destroyHandoverContainer(UUID uuid) throws SQLException {
        String sql = "DELETE FROM handover_container WHERE uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        }
    }
}
