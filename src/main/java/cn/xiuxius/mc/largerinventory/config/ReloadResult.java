package cn.xiuxius.mc.largerinventory.config;

import java.util.Set;

/**
 * {@link Reloadable#onReload} 的返回结果。
 *
 * <ul>
 *   <li>{@link Ok} — 本组件已成功完成 reload</li>
 *   <li>{@link WaitingFor} — 依赖的组件尚未 reload，本轮跳过，下轮重试</li>
 * </ul>
 */
public interface ReloadResult {

    /**
     * 构造成功结果。
     */
    static ReloadResult ok() {
        return new Ok();
    }

    /**
     * 构造等待结果，声明所需依赖。
     */
    @SafeVarargs
    static ReloadResult waitingFor(Class<? extends Reloadable>... deps) {
        return new WaitingFor(Set.of(deps));
    }

    /**
     * 成功完成 reload。
     */
    record Ok() implements ReloadResult {
    }

    /**
     * 等待指定组件先完成 reload。
     * {@code dependencies} 字段仅用于死锁日志，协调器不做逻辑判断。
     */
    record WaitingFor(Set<Class<? extends Reloadable>> dependencies) implements ReloadResult {
    }
}
