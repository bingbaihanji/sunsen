package com.bingbaihanji.sunsen.api.event.builtin;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

/**
 * 插件热重载成功后触发.
 * <p>
 * 与 {@link PluginUnloadedEvent} + {@link PluginLoadedEvent} 的区别:
 * 后两者都会发布(保持向后兼容),此事件额外携带新旧两个描述符,
 * 方便需要感知版本变化的宿主代码使用.
 */
public class PluginReloadedEvent extends AbstractPluginEvent {

    private final PluginDescriptor oldDescriptor;
    private final PluginDescriptor newDescriptor;

    /**
     * @param oldDescriptor 热重载前的插件描述符
     * @param newDescriptor 热重载后的插件描述符
     */
    public PluginReloadedEvent(PluginDescriptor oldDescriptor, PluginDescriptor newDescriptor) {
        super(newDescriptor.id());
        this.oldDescriptor = oldDescriptor;
        this.newDescriptor = newDescriptor;
    }

    public PluginDescriptor getOldDescriptor() {
        return oldDescriptor;
    }

    public PluginDescriptor getNewDescriptor() {
        return newDescriptor;
    }
}
