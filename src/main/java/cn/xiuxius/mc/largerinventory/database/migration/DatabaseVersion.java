package cn.xiuxius.mc.largerinventory.database.migration;

/**
 * 数据库版本枚举，每个版本关联一个迁移实现。
 * 新增版本只需：1) 添加枚举值  2) 创建对应的 Migration 类
 */
public enum DatabaseVersion {
    V1(1, null),
    V2(2, new V2CreatePageNames());

    private final int version;
    private final Migration migration;

    DatabaseVersion(int version, Migration migration) {
        this.version = version;
        this.migration = migration;
    }

    public int getVersion() {
        return version;
    }

    public Migration getMigration() {
        return migration;
    }

    /**
     * 获取最新版本号
     */
    public static int latest() {
        DatabaseVersion[] values = values();
        return values[values.length - 1].version;
    }
}
