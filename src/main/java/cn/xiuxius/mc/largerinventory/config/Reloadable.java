package cn.xiuxius.mc.largerinventory.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/**
 * 支持热更新的组件接口。
 *
 * <p>实现此接口的组件在 reload 时由 {@link ReloadCoordinator} 调度。
 * 每个组件负责声明自己的依赖：若所需组件尚未完成 reload，
 * 返回 {@link ReloadResult#waitingFor} 即可，协调器会自动重试。
 */
public interface Reloadable {

    /**
     * 执行热更新逻辑。
     *
     * @param newConfig 已通过校验的新配置
     * @param plugin    插件实例
     * @param reloaded  本轮已完成 reload 的组件类型集合
     * @return {@link ReloadResult#ok()} 表示成功；
     * {@link ReloadResult#waitingFor} 表示需等待指定组件先完成
     */
    ReloadResult onReload(PluginConfig newConfig, JavaPlugin plugin,
                          Set<Class<? extends Reloadable>> reloaded);
}
