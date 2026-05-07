package com.bingbaihanji.sunsen.core.lifecycle;

import com.bingbaihanji.sunsen.api.IllegalStateTransitionException;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginState;
import com.bingbaihanji.sunsen.api.event.builtin.PluginFailedEvent;
import com.bingbaihanji.sunsen.core.PluginEventBus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 插件生命周期状态机,负责管理插件从 CREATED 到 UNLOADED 的完整状态流转
 * <p>
 * 所有状态转换均通过 CAS 原子操作完成,确保并发安全;非法转换会抛出
 * {@link IllegalStateTransitionException},异常场景可通过 {@link #forceSet} 强制回退
 */
public class LifecycleStateMachine {

    // 合法状态转换表:key 为起始状态,value 为可达的目标状态集合
    private static final Map<PluginState, Set<PluginState>> VALID_TRANSITIONS;

    static {
        Map<PluginState, Set<PluginState>> map = new EnumMap<>(PluginState.class);
        // 定义合法转换
        map.put(PluginState.CREATED, EnumSet.of(PluginState.RESOLVED, PluginState.LOADED, PluginState.FAILED));
        map.put(PluginState.RESOLVED, EnumSet.of(PluginState.LOADED, PluginState.UNLOADED, PluginState.FAILED));
        map.put(PluginState.LOADED, EnumSet.of(PluginState.STARTING, PluginState.UNLOADED, PluginState.FAILED));
        map.put(PluginState.STARTING, EnumSet.of(PluginState.ACTIVE, PluginState.UNLOADED, PluginState.FAILED));
        map.put(PluginState.ACTIVE, EnumSet.of(PluginState.STOPPING, PluginState.UNLOADED, PluginState.FAILED));
        map.put(PluginState.STOPPING, EnumSet.of(PluginState.STOPPED, PluginState.UNLOADED, PluginState.FAILED));
        map.put(PluginState.STOPPED, EnumSet.of(PluginState.STARTING, PluginState.UNLOADED));
        map.put(PluginState.FAILED, EnumSet.of(PluginState.UNLOADED));
        // UNLOADED 为终态,无出边
        VALID_TRANSITIONS = Map.copyOf(map);
    }

    // 插件 ID -> 当前状态的原子引用
    private final Map<String, AtomicReference<PluginState>> states = new ConcurrentHashMap<>();
    // 事件总线,用于发布生命周期失败事件
    private final PluginEventBus eventBus;

    /**
     * @param eventBus 事件总线实例
     */
    public LifecycleStateMachine(PluginEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 初始化插件状态
     *
     * @param pluginId 插件 ID
     * @param initial  初始状态
     */
    public void init(String pluginId, PluginState initial) {
        states.put(pluginId, new AtomicReference<>(initial));
    }

    /**
     * 获取插件当前状态
     *
     * @param pluginId 插件 ID
     * @return 当前状态,未注册时返回 null
     */
    public PluginState getState(String pluginId) {
        AtomicReference<PluginState> ref = states.get(pluginId);
        return ref != null ? ref.get() : null;
    }

    /**
     * CAS 状态转换
     *
     * @throws IllegalStateTransitionException 非法转换
     */
    public void transition(String pluginId, PluginState from, PluginState to) {
        AtomicReference<PluginState> ref = states.get(pluginId);
        if (ref == null) {
            throw new IllegalStateTransitionException("插件 " + pluginId + " 未注册状态机");
        }
        if (!isValid(from, to)) {
            throw new IllegalStateTransitionException(
                    "插件 " + pluginId + " 不允许从 " + from + " 转换到 " + to
            );
        }
        if (!ref.compareAndSet(from, to)) {
            throw new IllegalStateTransitionException(
                    "插件 " + pluginId + " 当前状态不是 " + from + ",无法转换到 " + to
            );
        }
    }

    /**
     * 将状态强制设置为指定值(不校验转换合法性),用于异常回退场景
     */
    public void forceSet(String pluginId, PluginState to) {
        AtomicReference<PluginState> ref = states.get(pluginId);
        if (ref != null) {
            ref.set(to);
        }
    }

    /**
     * 插件完全卸载后,从状态机中移除其状态条目,释放内存
     */
    public void remove(String pluginId) {
        states.remove(pluginId);
    }

    /**
     * 执行生命周期阶段,捕获异常并转为 FAILED 状态
     * 注意:若阶段执行抛出异常,本方法会发布 {@link PluginFailedEvent} 后将异常继续抛出,
     * 由调用方决定是否进一步处理
     *
     * @param phase      要执行的生命周期阶段
     * @param descriptor 插件描述符
     * @param failState  失败时记录的状态
     * @throws RuntimeException 若阶段执行抛出异常
     */
    public void executePhase(Runnable phase, PluginDescriptor descriptor, PluginState failState) {
        try {
            phase.run();
        } catch (Exception e) {
            forceSet(descriptor.id(), PluginState.FAILED);
            eventBus.publish(new PluginFailedEvent(descriptor, failState, e));
            throw e;
        }
    }

    private boolean isValid(PluginState from, PluginState to) {
        Set<PluginState> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
