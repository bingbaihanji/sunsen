package com.bingbaihanji.sunsen.api;

import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;

import java.nio.file.Path;
import java.util.List;

/**
 * 插件运行时上下文,作为插件访问框架能力的唯一入口
 */
public interface PluginContext {

    /**
     * 获取当前插件的描述符
     */
    PluginDescriptor getDescriptor();

    /**
     * 获取插件专属工作目录
     */
    Path getPluginWorkDir();

    /**
     * 读取当前插件的配置项
     */
    String getProperty(String key);

    /**
     * 读取当前插件的配置项,带默认值
     */
    String getProperty(String key, String defaultValue);

    /**
     * 获取指定扩展点的所有实现
     */
    <T> List<T> getExtensions(Class<T> extensionPoint);

    /**
     * 发布事件至框架事件总线
     */
    void publishEvent(PluginEvent event);

    /**
     * 订阅事件
     */
    <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener);

    /**
     * 取消订阅事件
     */
    <T extends PluginEvent> void unsubscribe(Class<T> eventType, PluginEventListener<T> listener);

    /**
     * 获取 PluginManager
     */
    PluginManager getPluginManager();
}
