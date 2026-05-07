package com.bingbaihanji.sunsen.api.event;

/**
 * 类型安全的事件监听器
 */
@FunctionalInterface
public interface PluginEventListener<T extends PluginEvent> {

    /**
     * 处理事件
     *
     * @param event 事件对象
     */
    void onEvent(T event);
}
