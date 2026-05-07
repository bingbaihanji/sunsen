package com.bingbaihanji.sunsen.api.event.builtin;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

/**
 * 插件进入 LOADED 状态时触发
 */
public class PluginLoadedEvent extends AbstractPluginEvent {

    // 已加载的插件描述符
    private final PluginDescriptor descriptor;

    /**
     * @param descriptor 已加载的插件描述符
     */
    public PluginLoadedEvent(PluginDescriptor descriptor) {
        super(descriptor.id());
        this.descriptor = descriptor;
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }
}
