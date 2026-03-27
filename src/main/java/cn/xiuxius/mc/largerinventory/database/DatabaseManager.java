package cn.xiuxius.mc.largerinventory.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.logging.Level;

/**
 * 数据库管理器
 * 负责SQLite连接管理、表创建和版本迁移
 */
public class DatabaseManager {

    /**
     * 当前数据库版本
     * 每次结构变更时递增
     */
    public static final int DATABASE_VERSION = 1;
    private final JavaPlugin plugin;
    private final String dbPath;
    private Connection connection;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dbPath = plugin.getDataFolder() + File.separator + "data.db";
    }

    /**
     * 获取当前数据库版本常量
     */
    public static int getDatabaseVersionConstant() {
        return DATABASE_VERSION;
    }

    /**
     * 初始化数据库
     */
    public void init() {
        try {
            // 确保数据文件夹存在
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            // 建立连接
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            // 启用WAL模式提高并发安全
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }

            // 创建版本表
            createVersionTable();

            // 检查并执行迁移
            int currentVersion = getDatabaseVersion();
            if (currentVersion < DATABASE_VERSION) {
                migrate(currentVersion);
            }

            // 创建表
            createTables();

            plugin.getLogger().info("数据库初始化完成: " + dbPath + " (版本: " + DATABASE_VERSION + ")");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "SQLite驱动未找到", e);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "数据库连接失败", e);
        }
    }

    /**
     * 创建版本表
     */
    private void createVersionTable() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS db_version (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        version INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    /**
     * 获取数据库版本
     */
    private int getDatabaseVersion() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT version FROM db_version WHERE id = 1");
            if (rs.next()) {
                return rs.getInt("version");
            }
        }
        // 新数据库，初始化版本
        setDatabaseVersion(DATABASE_VERSION);
        return DATABASE_VERSION;
    }

    /**
     * 设置数据库版本
     */
    private void setDatabaseVersion(int version) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    INSERT OR REPLACE INTO db_version (id, version, updated_at)
                    VALUES (1, """ + version + ", " + System.currentTimeMillis() + ")"
            );
        }
    }

    /**
     * 执行数据库迁移
     *
     * @param fromVersion 当前版本
     */
    private void migrate(int fromVersion) throws SQLException {
        plugin.getLogger().info("开始数据库迁移: 从版本 " + fromVersion + " 到 " + DATABASE_VERSION);

        connection.setAutoCommit(false);
        try {
            // 版本迁移逻辑
            // if (fromVersion < 2) {
            //     migrateToV2();
            // }
            // if (fromVersion < 3) {
            //     migrateToV3();
            // }

            // 更新版本号
            setDatabaseVersion(DATABASE_VERSION);
            connection.commit();
            plugin.getLogger().info("数据库迁移完成");
        } catch (SQLException e) {
            connection.rollback();
            plugin.getLogger().log(Level.SEVERE, "数据库迁移失败，已回滚", e);
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * 创建数据库表
     */
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // 玩家物品数据
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

            // 玩家元数据
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

            // 备份表
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS inventory_backup (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        slot_index INTEGER NOT NULL,
                        page_number INTEGER NOT NULL,
                        item_data BLOB,
                        reason TEXT,
                        data_version INTEGER DEFAULT 1,
                        backup_time INTEGER NOT NULL,
                        restore_before INTEGER
                    )
                    """);

            // 交接容器数据
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

            // 创建索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_uuid ON player_inventory(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_page ON player_inventory(uuid, page_number)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_handover_uuid ON handover_container(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_name ON player_meta(player_name)");
        }
    }

    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        }
        return connection;
    }

    /**
     * 关闭数据库连接
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("数据库连接已关闭");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "关闭数据库连接时出错", e);
            }
        }
    }
}
