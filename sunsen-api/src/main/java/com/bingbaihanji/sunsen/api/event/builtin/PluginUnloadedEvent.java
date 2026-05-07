package com.bingbaihanji.sunsen.api.event.builtin;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

/**
 * 插件进入 UNLOADED 状态时触发
 */
public class PluginUnloadedEvent extends AbstractPluginEvent {

    // 已卸载的插件描述符
    private final PluginDescriptor descriptor;

    /**
     * @param descriptor 已卸载的插件描述符
     */
    public PluginUnloadedEvent(PluginDescriptor descriptor) {
        super(descriptor.id());
        this.descriptor = descriptor;
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }
}
