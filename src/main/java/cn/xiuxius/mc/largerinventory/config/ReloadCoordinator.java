package cn.xiuxius.mc.largerinventory.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 热更新调度器。
 *
 * <p>采用迭代推进策略：每轮遍历所有 pending 组件，成功则移出队列；
 * 若某轮无任何进度（所有剩余组件均返回 {@link ReloadResult.WaitingFor}），
 * 视为循环依赖，记录 severe 日志并提前返回 {@code false}。
 */
public class ReloadCoordinator {

    private final JavaPlugin plugin;
    private final List<Reloadable> components = new ArrayList<>();

    public ReloadCoordinator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册需要热更新的组件，按注册顺序作为初始迭代顺序。
     */
    public void register(Reloadable component) {
        components.add(component);
    }

    /**
     * 对所有已注册组件执行热更新。
     *
     * @param newConfig 已通过校验的新配置
     * @return 全部成功返回 {@code true}；检测到循环依赖返回 {@code false}
     */
    public boolean execute(PluginConfig newConfig) {
        Logger log = plugin.getLogger();
        List<Reloadable> pending = new ArrayList<>(components);
        Set<Class<? extends Reloadable>> reloaded = new HashSet<>();

        while (!pending.isEmpty()) {
            boolean progress = false;
            Iterator<Reloadable> it = pending.iterator();
            while (it.hasNext()) {
                Reloadable r = it.next();
                ReloadResult result = r.onReload(newConfig, plugin, reloaded);
                if (result instanceof ReloadResult.Ok) {
                    reloaded.add(r.getClass());
                    it.remove();
                    progress = true;
                }
                // WaitingFor: 保留在 pending，下轮重试
            }
            if (!progress) {
                // 无进度 = 循环依赖或依赖不可满足
                String deadlocked = pending.stream()
                        .map(r -> {
                            String name = r.getClass().getSimpleName();
                            if (r.onReload(newConfig, plugin, reloaded) instanceof ReloadResult.WaitingFor w) {
                                String deps = w.dependencies().stream()
                                        .map(Class::getSimpleName)
                                        .collect(Collectors.joining(", "));
                                return name + " (waiting for: " + deps + ")";
                            }
                            return name;
                        })
                        .collect(Collectors.joining("; "));
                log.severe("Reload deadlock detected — circular or unsatisfiable dependencies: " + deadlocked);
                return false;
            }
        }
        return true;
    }
}
