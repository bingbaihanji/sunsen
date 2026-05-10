package com.bingbaihanji.sunsen.api.event.builtin;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

/**
 * 插件通过 {@code uninstallPlugin(String)} 卸载成功后触发.
 * <p>
 * 卸载 = 停止 + 卸载 + 删除 JAR 文件,三步完成后发布此事件.
 */
public class PluginUninstalledEvent extends AbstractPluginEvent {

    private final PluginDescriptor descriptor;

    /**
     * @param descriptor 被卸载插件的描述符
     */
    public PluginUninstalledEvent(PluginDescriptor descriptor) {
        super(descriptor.id());
        this.descriptor = descriptor;
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }
}
