package cn.xiuxius.mc.largerinventory.database.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V3: 创建备份相关表
 */
public class V3CreateBackupTables implements Migration {
    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_backup_meta_uuid ON backup_meta(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_backup_items_name ON inventory_backups(backup_name, uuid)");
        }
    }
}
