package com.bingbaihanji.sunsen.api.event.builtin;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

import java.nio.file.Path;

/**
 * 插件通过 {@code installPlugin(Path)} 安装成功后触发.
 * <p>
 * 安装 = 将外部 JAR 复制到插件目录 + 加载 + 启动,三步全部成功才发布此事件.
 */
public class PluginInstalledEvent extends AbstractPluginEvent {

    private final PluginDescriptor descriptor;
    private final Path installedJarPath;

    /**
     * @param descriptor       已安装插件的描述符
     * @param installedJarPath 在插件目录中的 JAR 路径
     */
    public PluginInstalledEvent(PluginDescriptor descriptor, Path installedJarPath) {
        super(descriptor.id());
        this.descriptor = descriptor;
        this.installedJarPath = installedJarPath;
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }

    public Path getInstalledJarPath() {
        return installedJarPath;
    }
}
