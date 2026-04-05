package cn.xiuxius.mc.largerinventory.database;

import cn.xiuxius.mc.largerinventory.config.PluginConfig;
import cn.xiuxius.mc.largerinventory.database.migration.DatabaseVersion;
import cn.xiuxius.mc.largerinventory.i18n.MessageKeys;
import cn.xiuxius.mc.largerinventory.i18n.MessageManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.logging.Level;

/**
 * 数据库管理器，支持 SQLite 和 MySQL。
 * <p>
 * SQLite 模式：使用 HikariCP 连接池（maximumPoolSize=1）解决写锁竞争。
 * MySQL 模式：使用 HikariCP 连接池，支持配置池大小。
 */
public class DatabaseManager {

    private final JavaPlugin plugin;
    private final MessageManager messageManager;
    private final boolean mysql;
    private final HikariDataSource dataSource;

    public DatabaseManager(JavaPlugin plugin, MessageManager messageManager, PluginConfig config) {
        this.plugin = plugin;
        this.messageManager = messageManager;
        this.mysql = config.isMysql();

        HikariConfig hikariConfig = new HikariConfig();
        if (mysql) {
            String jdbcUrl = "jdbc:mysql://" + config.getMysqlHost() + ":" + config.getMysqlPort()
                    + "/" + config.getMysqlDatabase()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8mb4&useUnicode=true";
            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(config.getMysqlUsername());
            hikariConfig.setPassword(config.getMysqlPassword());
            hikariConfig.setMaximumPoolSize(config.getMysqlPoolSize());
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        } else {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            String dbPath = dataFolder + File.separator + "data.db";
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
            hikariConfig.setMaximumPoolSize(1);
            hikariConfig.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;");
        }
        hikariConfig.setPoolName("LargerInventory-DB");

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public void init() {
        try {
            int targetVersion = DatabaseVersion.latest();
            try (Connection conn = getConnection()) {
                createVersionTable(conn);
                int currentVersion = getStoredVersion(conn);
                if (currentVersion < targetVersion) {
                    migrate(conn, currentVersion, targetVersion);
                }
                createTables(conn);
            }

            String dbInfo = mysql ? "MySQL" : "SQLite";
            plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.DB_INIT_COMPLETE,
                    "path", dbInfo, "version", targetVersion));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, messageManager.getLog(MessageKeys.Log.DB_INIT_FAILED), e);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean isMysql() {
        return mysql;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        plugin.getLogger().info(messageManager.getLog(MessageKeys.Log.DB_CONNECTION_CLOSED));
    }

    // ==================== 私有方法 ====================

    private void createVersionTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (mysql) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS db_version (
                            id INT PRIMARY KEY,
                            version INT NOT NULL,
                            updated_at BIGINT NOT NULL
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
            } else {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS db_version (
                            id INTEGER PRIMARY KEY CHECK (id = 1),
                            version INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
            }
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
        String sql = mysql
                ? "REPLACE INTO db_version (id, version, updated_at) VALUES (1, " + version + ", " + System.currentTimeMillis() + ")"
                : "INSERT OR REPLACE INTO db_version (id, version, updated_at) VALUES (1, " + version + ", " + System.currentTimeMillis() + ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
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
            if (mysql) {
                createTablesMysql(stmt);
            } else {
                createTablesSqlite(stmt);
            }
        }
    }

    private void createTablesSqlite(Statement stmt) throws SQLException {
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
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS backup_meta (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    backup_name TEXT NOT NULL,
                    uuid TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    description TEXT,
                    UNIQUE(backup_name, uuid)
                )
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS inventory_backups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    backup_name TEXT NOT NULL,
                    uuid TEXT NOT NULL,
                    page_number INTEGER NOT NULL,
                    slot_index INTEGER NOT NULL,
                    item_data BLOB,
                    created_at INTEGER NOT NULL,
                    UNIQUE(backup_name, uuid, page_number, slot_index)
                )
                """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_uuid ON player_inventory(uuid)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_page ON player_inventory(uuid, page_number)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_handover_uuid ON handover_container(uuid)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_name ON player_meta(player_name)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_page_names_uuid ON page_names(uuid)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_backup_meta_uuid ON backup_meta(uuid)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_backup_items_name ON inventory_backups(backup_name, uuid)");
    }

    private void createTablesMysql(Statement stmt) throws SQLException {
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_inventory (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    slot_index INT NOT NULL,
                    page_number INT NOT NULL,
                    item_data MEDIUMBLOB,
                    data_version INT DEFAULT 1,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE KEY uk_inv (uuid, slot_index, page_number),
                    INDEX idx_inv_uuid (uuid),
                    INDEX idx_inv_page (uuid, page_number)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_meta (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    uuid VARCHAR(36) NOT NULL UNIQUE,
                    player_name VARCHAR(64),
                    current_page INT DEFAULT 0,
                    max_page INT DEFAULT 0,
                    data_version INT DEFAULT 1,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    INDEX idx_player_name (player_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS handover_container (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    uuid VARCHAR(36) NOT NULL,
                    slot_index INT NOT NULL,
                    item_data MEDIUMBLOB NOT NULL,
                    data_version INT DEFAULT 1,
                    created_at BIGINT NOT NULL,
                    UNIQUE KEY uk_handover (uuid, slot_index),
                    INDEX idx_handover_uuid (uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS page_names (
                    uuid VARCHAR(36) NOT NULL,
                    page_number INT NOT NULL,
                    page_name VARCHAR(128) NOT NULL,
                    UNIQUE KEY uk_page_names (uuid, page_number),
                    INDEX idx_page_names_uuid (uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS backup_meta (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    backup_name VARCHAR(128) NOT NULL,
                    uuid VARCHAR(36) NOT NULL,
                    created_at BIGINT NOT NULL,
                    description TEXT,
                    UNIQUE KEY uk_backup_meta (backup_name, uuid),
                    INDEX idx_backup_meta_uuid (uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS inventory_backups (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    backup_name VARCHAR(128) NOT NULL,
                    uuid VARCHAR(36) NOT NULL,
                    page_number INT NOT NULL,
                    slot_index INT NOT NULL,
                    item_data MEDIUMBLOB,
                    created_at BIGINT NOT NULL,
                    UNIQUE KEY uk_backup_items (backup_name, uuid, page_number, slot_index),
                    INDEX idx_backup_items_name (backup_name, uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }
}
