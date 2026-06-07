package com.bingbaihanji.sunsen.api;

/**
 * 插件权限不足时抛出
 *
 * @author 冰白寒祭
 * @date 2025
 */
public class PluginPermissionException extends SunsenException {

    /**
     * @param message 权限不足的详细描述
     */
    public PluginPermissionException(String message) {
        super(message);
    }
}
