package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * 框架内置事件总线,默认同步分发,支持自定义线程池切换为异步模式
 */
public class PluginEventBus {

    private static final System.Logger LOGGER = System.getLogger(PluginEventBus.class.getName());

    // 事件类型 -> 订阅者列表
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Subscription<?>>> listeners = new ConcurrentHashMap<>();
    // 事件执行器,默认同步执行
    private final Executor executor;

    /**
     * 构造默认同步事件总线
     */
    public PluginEventBus() {
        this.executor = Runnable::run; // 同步执行
    }

    /**
     * 构造自定义执行器的事件总线
     *
     * @param executor 异步执行线程池
     */
    public PluginEventBus(Executor executor) {
        this.executor = executor;
    }

    @SuppressWarnings("unchecked")
    public <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new Subscription<>((PluginEventListener<PluginEvent>) listener, null));
    }

    /**
     * 订阅指定类型的事件,并绑定插件 ID 以便批量取消订阅
     *
     * @param eventType 事件类型
     * @param listener  事件监听器
     * @param pluginId  注册该订阅的插件 ID
     */
    @SuppressWarnings("unchecked")
    public <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener, String pluginId) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new Subscription<>((PluginEventListener<PluginEvent>) listener, pluginId));
    }

    public <T extends PluginEvent> void unsubscribe(Class<T> eventType, PluginEventListener<T> listener) {
        CopyOnWriteArrayList<Subscription<?>> list = listeners.get(eventType);
        if (list == null) return;
        list.removeIf(sub -> sub.listener == listener);
    }

    /**
     * 取消指定插件注册的所有订阅
     */
    public void unsubscribeAll(String pluginId) {
        for (CopyOnWriteArrayList<Subscription<?>> list : listeners.values()) {
            list.removeIf(sub -> pluginId.equals(sub.pluginId));
        }
    }

    public void publish(PluginEvent event) {
        Class<? extends PluginEvent> eventClass = event.getClass();

        // 精确类型匹配(最常见路径,O(1))
        CopyOnWriteArrayList<Subscription<?>> exact = listeners.get(eventClass);
        if (exact != null) {
            dispatch(exact, event);
        }

        // 遍历父类型订阅(如订阅了 PluginEvent 基类或父事件接口)
        for (Map.Entry<Class<?>, CopyOnWriteArrayList<Subscription<?>>> entry : listeners.entrySet()) {
            if (entry.getKey() != eventClass && entry.getKey().isAssignableFrom(eventClass)) {
                dispatch(entry.getValue(), event);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(CopyOnWriteArrayList<Subscription<?>> list, PluginEvent event) {
        for (Subscription<?> sub : list) {
            executor.execute(() -> {
                try {
                    ((Subscription<PluginEvent>) sub).listener.onEvent(event);
                } catch (Exception e) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            () -> "事件监听器处理异常: " + sub.listener.getClass().getName(), e);
                }
            });
        }
    }

    /**
     * 订阅记录
     *
     * @param listener 事件监听器
     * @param pluginId 注册该订阅的插件 ID(框架级订阅可为空)
     */
    private record Subscription<T extends PluginEvent>(PluginEventListener<T> listener,
                                                       String pluginId) {
    }
}
