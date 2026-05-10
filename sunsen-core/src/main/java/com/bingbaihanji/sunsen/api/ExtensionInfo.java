package com.bingbaihanji.sunsen.api;

/**
 * 扩展元数据快照,不持有扩展实例引用.
 * <p>
 * 通过 {@link PluginManager#getExtensionInfos(Class)} 获取,
 * 适用于管理界面、监控、日志等只需要元数据而不需要调用扩展逻辑的场景.
 *
 * @param extensionId        扩展唯一标识({@code @Extension.id},默认为实现类全限定名)
 * @param order              排序权重,值越小优先级越高
 * @param description        扩展描述({@code @Extension.description})
 * @param pluginId           所属插件 ID
 * @param singleton          是否单例
 * @param extensionPointType 所实现的扩展点接口类型
 */
public record ExtensionInfo(
        String extensionId,
        int order,
        String description,
        String pluginId,
        boolean singleton,
        Class<?> extensionPointType
) {
}
