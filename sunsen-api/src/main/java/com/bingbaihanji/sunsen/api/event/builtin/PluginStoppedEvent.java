package com.bingbaihanji.sunsen.api.event.builtin;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

/**
 * 插件进入 STOPPED 状态时触发
 */
public class PluginStoppedEvent extends AbstractPluginEvent {

    // 已停止的插件描述符
    private final PluginDescriptor descriptor;

    /**
     * @param descriptor 已停止的插件描述符
     */
    public PluginStoppedEvent(PluginDescriptor descriptor) {
        super(descriptor.id());
        this.descriptor = descriptor;
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }
}
