package com.bingbaihanji.sunsen.api;

/**
 * 插件状态非法转换时抛出
 */
public class IllegalStateTransitionException extends SunsenException {

    /**
     * @param message 非法状态转换的详细描述
     */
    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
