package com.bingbaihanji.sunsen.api;

import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadFactory;

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
     * 重新从工作目录的 {@code config.properties} 加载配置.
     * <p>
     * 适用于在不重启插件的情况下动态刷新配置文件内容.
     */
    void reloadConfig();

    /**
     * 获取指定扩展点的所有实现
     */
    <T> List<T> getExtensions(Class<T> extensionPoint);

    /**
     * 获取指定扩展点的元数据列表,不持有扩展实例引用.
     *
     * @param extensionPoint 扩展点接口类型
     * @return 按 order 升序排列的 {@link ExtensionInfo} 列表
     */
    <T> List<ExtensionInfo> getExtensionInfos(Class<T> extensionPoint);

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

    /**
     * 获取插件专属 {@link ThreadFactory}.
     * <p>
     * 通过此工厂创建的线程:
     * <ul>
     *   <li>以 {@code <pluginId>-thread-N} 格式命名,便于线程转储定位</li>
     *   <li>设为守护线程({@code daemon = true}),不阻止 JVM 退出</li>
     *   <li>归属于插件专属 {@link ThreadGroup},插件卸载时框架会统一中断</li>
     * </ul>
     * 推荐与 {@link com.bingbaihanji.sunsen.api.support.ManagedScheduler} 配合使用.
     */
    ThreadFactory getThreadFactory();
}
