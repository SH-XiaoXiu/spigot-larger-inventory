package cn.xiuxius.mc.largerinventory.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.logging.Level;

/**
 * 数据库管理器（无状态连接模型）
 *
 * getConnection() 每次返回一个新连接，调用方必须在 try-with-resources 中使用。
 * SQLite WAL 模式原生支持多连接并发读写，无需连接池。
 */
public class DatabaseManager {

    public static final int DATABASE_VERSION = 1;

    private final JavaPlugin plugin;
    private final String dbPath;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dbPath = plugin.getDataFolder() + File.separator + "data.db";
    }

    public static int getDatabaseVersionConstant() {
        return DATABASE_VERSION;
    }

    /** 初始化：建表、版本迁移，使用一次性局部连接。 */
    public void init() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            Class.forName("org.sqlite.JDBC");

            try (Connection conn = openConnection()) {
                createVersionTable(conn);
                int currentVersion = getDatabaseVersion(conn);
                if (currentVersion < DATABASE_VERSION) {
                    migrate(conn, currentVersion);
                }
                createTables(conn);
            }

            plugin.getLogger().info("数据库初始化完成: " + dbPath + " (版本: " + DATABASE_VERSION + ")");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "SQLite 驱动未找到", e);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "数据库初始化失败", e);
        }
    }

    /**
     * 获取新的数据库连接。调用方负责关闭（建议 try-with-resources）。
     * 可安全在任意线程调用。
     */
    public Connection getConnection() throws SQLException {
        return openConnection();
    }

    /** 无持久连接，此方法仅做记录。 */
    public void close() {
        plugin.getLogger().info("数据库连接管理器已关闭");
    }

    // ==================== 私有方法 ====================

    private Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    private void createVersionTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS db_version (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        version INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    private int getDatabaseVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT version FROM db_version WHERE id = 1");
            if (rs.next()) {
                return rs.getInt("version");
            }
        }
        setDatabaseVersion(conn, DATABASE_VERSION);
        return DATABASE_VERSION;
    }

    private void setDatabaseVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT OR REPLACE INTO db_version (id, version, updated_at) VALUES (1, "
                            + version + ", " + System.currentTimeMillis() + ")"
            );
        }
    }

    private void migrate(Connection conn, int fromVersion) throws SQLException {
        plugin.getLogger().info("开始数据库迁移: v" + fromVersion + " → v" + DATABASE_VERSION);
        conn.setAutoCommit(false);
        try {
            // if (fromVersion < 2) { migrateToV2(conn); }
            setDatabaseVersion(conn, DATABASE_VERSION);
            conn.commit();
            plugin.getLogger().info("数据库迁移完成");
        } catch (SQLException e) {
            conn.rollback();
            plugin.getLogger().log(Level.SEVERE, "数据库迁移失败，已回滚", e);
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_inventory (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        slot_index INTEGER NOT NULL,
                        page_number INTEGER NOT NULL,
                        item_data BLOB,
                        data_version INTEGER DEFAULT 1,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(uuid, slot_index, page_number)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_meta (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL UNIQUE,
                        player_name TEXT,
                        current_page INTEGER DEFAULT 0,
                        max_page INTEGER DEFAULT 0,
                        data_version INTEGER DEFAULT 1,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS handover_container (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        slot_index INTEGER NOT NULL,
                        item_data BLOB NOT NULL,
                        data_version INTEGER DEFAULT 1,
                        created_at INTEGER NOT NULL,
                        UNIQUE(uuid, slot_index)
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_uuid ON player_inventory(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_page ON player_inventory(uuid, page_number)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_handover_uuid ON handover_container(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_name ON player_meta(player_name)");
        }
    }
}
