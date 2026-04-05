package cn.xiuxius.mc.largerinventory.database.migration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库迁移接口
 */
@FunctionalInterface
public interface Migration {
    void apply(Connection conn) throws SQLException;
}
