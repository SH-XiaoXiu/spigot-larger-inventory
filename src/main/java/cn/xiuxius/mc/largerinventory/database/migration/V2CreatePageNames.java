package cn.xiuxius.mc.largerinventory.database.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V2: 创建页面���名表
 */
public class V2CreatePageNames implements Migration {
    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS page_names (
                        uuid TEXT NOT NULL,
                        page_number INTEGER NOT NULL,
                        page_name TEXT NOT NULL,
                        UNIQUE(uuid, page_number)
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_page_names_uuid ON page_names(uuid)");
        }
    }
}
