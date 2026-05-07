package com.bingbaihanji.sunsen.api.event;

/**
 * 内置事件的抽象基类
 */
public abstract class AbstractPluginEvent implements PluginEvent {

    // 触发事件的插件 ID
    private final String sourcePluginId;
    // 事件发生的时间戳(毫秒)
    private final long timestamp;

    /**
     * @param sourcePluginId 触发事件的插件 ID
     */
    protected AbstractPluginEvent(String sourcePluginId) {
        this.sourcePluginId = sourcePluginId;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String getSourcePluginId() {
        return sourcePluginId;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }
}
