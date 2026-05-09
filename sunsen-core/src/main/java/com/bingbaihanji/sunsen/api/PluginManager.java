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
     * 与 {@link #loadPlugins()} 行为相同,但同时收集加载失败的结果并返回.
     * <p>
     * 加载失败的插件不影响其他插件的加载,失败信息通过返回值提供给调用方.
     *
     * @return 所有加载失败项的列表;全部成功则返回空列表
     */
    List<PluginLoadError> loadPluginsWithResult();

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
     * <p>
     * 若新 JAR 加载失败,框架将自动尝试用原 JAR 回滚.
     */
    void reloadPlugin(String pluginId, Path newJarPath);

    /**
     * 使用原 JAR 路径原地热重载.
     * <p>
     * 适用于外部工具已将 JAR 替换完毕,需通知框架重新加载的场景.
     */
    void reloadPlugin(String pluginId);

    /**
     * 将外部 JAR 复制到插件目录并加载启动.
     * <p>
     * 等价于:复制 JAR → {@link #loadPlugin(Path)} → {@link #startPlugin(String)}.
     * 成功后发布 {@code PluginInstalledEvent}.
     *
     * @param source 源 JAR 文件路径,可以位于插件目录之外
     */
    void installPlugin(Path source);

    /**
     * 停止并卸载插件,同时删除对应的 JAR 文件.
     * <p>
     * 等价于:{@link #stopPlugin(String)} → {@link #unloadPlugin(String)} → 删除 JAR.
     * 成功后发布 {@code PluginUninstalledEvent}.
     *
     * @param pluginId 要卸载的插件 ID
     */
    void uninstallPlugin(String pluginId);

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
     * 按状态过滤已加载的插件描述符列表
     *
     * @param state 目标状态
     */
    default List<PluginDescriptor> getPlugins(PluginState state) {
        return getPlugins().stream()
                .filter(d -> state == getPluginState(d.id()))
                .toList();
    }

    /**
     * 判断指定插件是否处于 ACTIVE 状态
     *
     * @param pluginId 插件 ID
     */
    default boolean isPluginActive(String pluginId) {
        return getPluginState(pluginId) == PluginState.ACTIVE;
    }

    /**
     * 获取指定插件的当前状态
     *
     * @param pluginId 插件 ID
     * @return 插件状态,插件未知时返回 {@code null}
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

    /**
     * 获取指定扩展点的元数据列表,不持有扩展实例引用.
     * <p>
     * 适用于管理界面、监控日志等只需要元数据而不调用扩展逻辑的场景.
     *
     * @param extensionPoint 扩展点接口类型
     * @return 按 order 升序排列的 {@link ExtensionInfo} 列表
     */
    <T> List<ExtensionInfo> getExtensionInfos(Class<T> extensionPoint);

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
