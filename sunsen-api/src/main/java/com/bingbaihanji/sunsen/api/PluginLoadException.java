package com.bingbaihanji.sunsen.api;

/**
 * 插件加载过程中发生的异常
 */
public class PluginLoadException extends SunsenException {

    /**
     * @param message 异常描述信息
     */
    public PluginLoadException(String message) {
        super(message);
    }

    /**
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public PluginLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}