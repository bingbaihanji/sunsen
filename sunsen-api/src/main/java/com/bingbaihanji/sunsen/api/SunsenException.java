package com.bingbaihanji.sunsen.api;

/**
 * Sunsen 框架所有异常的根类
 */
public class SunsenException extends RuntimeException {

    /**
     * @param message 异常描述信息
     */
    public SunsenException(String message) {
        super(message);
    }

    /**
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public SunsenException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @param cause 原始异常
     */
    public SunsenException(Throwable cause) {
        super(cause);
    }
}
