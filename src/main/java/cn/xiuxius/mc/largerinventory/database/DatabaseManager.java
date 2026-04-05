package cn.xiuxius.mc.largerinventory.database;

import cn.xiuxius.mc.largerinventory.database.migration.DatabaseVersion;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.logging.Level;

/**
 * 数据库管理��（无状���连接模型）
 * <p>
 * getConnection() 每次返回一个新连接���调用方���须在 try-with-resources ��使用。
 * SQLite WAL 模式原生支持多���接并发读写，无需连接池。
 */
public class DatabaseManager {

    private final JavaPlugin plugin;
    private final MessageManager messageManager;
    private final String dbPath;

    public DatabaseManager(JavaPlugin plugin, MessageManager messageManager) {
        this.plugin = plugin;
        this.messageManager = messageManager;
        this.dbPath = plugin.getDataFolder() + File.separator + "data.db";
    }

    /**
     * 初始化：建表、版本���移，使用一次性局部连接。
     */
    public void init() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            Class.forName("org.sqlite.JDBC");

            int targetVersion = DatabaseVersion.latest();
            try (Connection conn = openConnection()) {
                createVersionTable(conn);
                int currentVersion = getStoredVersion(conn);
                if (currentVersion < targetVersion) {
                    migrate(conn, currentVersion, targetVersion);
                }
                createTables(conn);
            }

            plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.DB_INIT_COMPLETE,
                    "path", dbPath, "version", targetVersion));
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, messageManager.getLog(MessageKeys.Log.DB_DRIVER_NOT_FOUND), e);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, messageManager.getLog(MessageKeys.Log.DB_INIT_FAILED), e);
        }
    }

    /**
     * 获取新���数据库连接。调用方负责关闭（建议 try-with-resources）。
     * 可安全在任意线程调���。
     */
    public Connection getConnection() throws SQLException {
        return openConnection();
    }

    /**
     * 无持久连接，此方���仅���记录。
     */
    public void close() {
        plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.DB_CONNECTION_CLOSED));
    }

    // ==================== 私有方法 ====================

    private Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");
            stmt.execute("PRAGMA busy_timeout=5000");
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

    private int getStoredVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT version FROM db_version WHERE id = 1");
            if (rs.next()) {
                return rs.getInt("version");
            }
        }
        int targetVersion = DatabaseVersion.latest();
        setDatabaseVersion(conn, targetVersion);
        return targetVersion;
    }

    private void setDatabaseVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT OR REPLACE INTO db_version (id, version, updated_at) VALUES (1, "
                            + version + ", " + System.currentTimeMillis() + ")"
            );
        }
    }

    private void migrate(Connection conn, int fromVersion, int toVersion) throws SQLException {
        plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.DB_MIGRATION_START,
                "from", fromVersion, "to", toVersion));
        conn.setAutoCommit(false);
        try {
            for (DatabaseVersion dbv : DatabaseVersion.values()) {
                if (dbv.getVersion() > fromVersion && dbv.getMigration() != null) {
                    dbv.getMigration().apply(conn);
                }
            }
            setDatabaseVersion(conn, toVersion);
            conn.commit();
            plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.DB_MIGRATION_COMPLETE));
        } catch (SQLException e) {
            conn.rollback();
            plugin.getLogger().log(Level.SEVERE, messageManager.getLog(MessageKeys.Log.DB_MIGRATION_FAILED), e);
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
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS page_names (
                        uuid TEXT NOT NULL,
                        page_number INTEGER NOT NULL,
                        page_name TEXT NOT NULL,
                        UNIQUE(uuid, page_number)
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_uuid ON player_inventory(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_page ON player_inventory(uuid, page_number)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_handover_uuid ON handover_container(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_name ON player_meta(player_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_page_names_uuid ON page_names(uuid)");
        }
    }
}
