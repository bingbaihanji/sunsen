package com.bingbaihanji.sunsen.api.event.builtin;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

/**
 * 插件进入 ACTIVE 状态时触发
 */
public class PluginStartedEvent extends AbstractPluginEvent {

    // 已启动的插件描述符
    private final PluginDescriptor descriptor;

    /**
     * @param descriptor 已启动的插件描述符
     */
    public PluginStartedEvent(PluginDescriptor descriptor) {
        super(descriptor.id());
        this.descriptor = descriptor;
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }
}
