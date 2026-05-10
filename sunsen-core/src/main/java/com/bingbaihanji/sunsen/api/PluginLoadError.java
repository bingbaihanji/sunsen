package com.bingbaihanji.sunsen.api;

import java.nio.file.Path;

/**
 * 插件加载失败的错误记录.
 * <p>
 * 由 {@link PluginManager#loadPluginsWithResult()} 返回,表示批量加载过程中某个插件的加载异常.
 *
 * @param pluginId JAR 解析成功时为插件 ID;解析失败时为 JAR 文件名
 * @param jarPath  出错的 JAR 路径
 * @param cause    导致加载失败的异常
 */
public record PluginLoadError(String pluginId, Path jarPath, Exception cause) {
}
