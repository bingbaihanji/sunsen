package com.bingbaihanji.sunsen.demo.plain.event;

import com.bingbaihanji.sunsen.api.event.AbstractPluginEvent;

/**
 * 业务事件：插件向总线广播一条问候语
 * <p>
 * 通过继承 {@link AbstractPluginEvent}，自动获得 {@code sourcePluginId} 与 {@code timestamp}。
 * 任何监听 {@code GreetingEvent.class} 或其父类（如 {@code PluginEvent.class}）的订阅者都会收到。
 */
public class GreetingEvent extends AbstractPluginEvent {

    private final String message;

    public GreetingEvent(String sourcePluginId, String message) {
        super(sourcePluginId);
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
