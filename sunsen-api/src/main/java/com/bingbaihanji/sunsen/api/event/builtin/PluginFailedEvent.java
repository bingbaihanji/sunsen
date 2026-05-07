package com.bingbaihanji.sunsen.api.event.builtin;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginState;
import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

/**
 * 插件进入 FAILED 状态时触发
 */
public class PluginFailedEvent extends AbstractPluginEvent {

    // 发生失败的插件描述符
    private final PluginDescriptor descriptor;
    // 失败发生时的插件状态
    private final PluginState stateAtFailure;
    // 导致失败的异常
    private final Throwable cause;

    /**
     * @param descriptor     发生失败的插件描述符
     * @param stateAtFailure 失败发生时的插件状态
     * @param cause          导致失败的异常
     */
    public PluginFailedEvent(PluginDescriptor descriptor, PluginState stateAtFailure, Throwable cause) {
        super(descriptor.id());
        this.descriptor = descriptor;
        this.stateAtFailure = stateAtFailure;
        this.cause = cause;
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }

    public PluginState getStateAtFailure() {
        return stateAtFailure;
    }

    public Throwable getCause() {
        return cause;
    }
}
