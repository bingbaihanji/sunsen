package com.bingbaihanji.sunsen.api;

import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 插件管理器接口
 */
public interface PluginManager {

    //   批量操作

    /**
     * 扫描 pluginsDir,依赖拓扑排序后批量加载所有插件
     */
    void loadPlugins();

    /**
     * 按拓扑顺序批量启动所有 LOADED 状态的插件
     */
    void startPlugins();

    /**
     * 按拓扑逆序批量停止所有 ACTIVE 状态的插件
     */
    void stopPlugins();

    /**
     * 批量卸载所有 STOPPED 状态的插件,释放 ClassLoader
     */
    void unloadPlugins();

    //   单插件操作(热重载核心 API)  

    /**
     * 从指定 JAR 路径加载单个插件,返回其描述符
     */
    PluginDescriptor loadPlugin(Path jarPath);

    /**
     * 启动指定 ID 的插件
     *
     * @param pluginId 插件 ID
     */
    void startPlugin(String pluginId);

    /**
     * 停止指定 ID 的插件
     *
     * @param pluginId 插件 ID
     */
    void stopPlugin(String pluginId);

    /**
     * 卸载指定 ID 的插件
     *
     * @param pluginId 插件 ID
     */
    void unloadPlugin(String pluginId);

    /**
     * 原子性热替换:stop → unload → load(新 JAR)→ start
     */
    void reloadPlugin(String pluginId, Path newJarPath);

    //   查询    

    /**
     * 按 ID 获取插件描述符
     *
     * @param pluginId 插件 ID
     * @return 插件描述符 Optional
     */
    Optional<PluginDescriptor> getPlugin(String pluginId);

    /**
     * 获取所有已加载插件的描述符列表
     */
    List<PluginDescriptor> getPlugins();

    /**
     * 获取指定插件的当前状态
     *
     * @param pluginId 插件 ID
     * @return 插件状态
     */
    PluginState getPluginState(String pluginId);

    //   扩展访问  

    /**
     * 返回指定扩展点的所有实现,按 @Extension.order 升序排列
     */
    <T> List<T> getExtensions(Class<T> extensionPoint);

    /**
     * 按 id 精确获取单个扩展实例
     */
    <T> Optional<T> getExtension(Class<T> extensionPoint, String extensionId);

    //   事件总线  

    /**
     * 发布事件至框架事件总线
     *
     * @param event 事件对象
     */
    void publishEvent(PluginEvent event);

    /**
     * 订阅指定类型的事件
     *
     * @param eventType 事件类型
     * @param listener  事件监听器
     */
    <T extends PluginEvent> void subscribe(
            Class<T> eventType, PluginEventListener<T> listener);

    /**
     * 取消订阅指定类型的事件
     *
     * @param eventType 事件类型
     * @param listener  事件监听器
     */
    <T extends PluginEvent> void unsubscribe(
            Class<T> eventType, PluginEventListener<T> listener);

    //   钩子配置  

    /**
     * 设置扩展实例生命周期钩子(HOOK)
     *
     * @param registrar 扩展注册器实现
     */
    void setExtensionRegistrar(ExtensionRegistrar registrar);
}
