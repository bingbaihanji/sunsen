package com.bingbaihanji.sunsen.api.event;

/**
 * 所有事件的基础接口
 */
public interface PluginEvent {

    /**
     * 触发此事件的插件 ID
     */
    String getSourcePluginId();

    /**
     * 事件发生时间戳
     */
    long getTimestamp();
}
