package com.bingbaihanji.sunsen.demo.plain.hello;

import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.api.support.AbstractPlugin;
import com.bingbaihanji.sunsen.api.support.PluginProperties;
import com.bingbaihanji.sunsen.demo.plain.Greeter;
import com.bingbaihanji.sunsen.demo.plain.event.GreetingEvent;

import java.util.List;

/**
 * Hello 演示插件
 * <p>
 * 高级用法演示：
 * <ul>
 *   <li>继承 {@link AbstractPlugin} 而非直接实现 {@code Plugin}</li>
 *   <li>用 {@link PluginProperties} 类型安全地读取 {@code config.properties}</li>
 *   <li>{@code onInitialized()} 中通过 {@code requirePermission} 提前发现权限缺失</li>
 *   <li>{@code onStart()} 中订阅 {@link GreetingEvent}，框架在 UNLOADED 时自动取消订阅</li>
 * </ul>
 */
public class HelloPlugin extends AbstractPlugin {

    /** 问候语后缀，从 config.properties 读取。 */
    static volatile String greetingSuffix = "";

    @Override
    protected void onInitialized() {
        requirePermission("event:subscribe");
        PluginProperties props = PluginProperties.of(context());
        greetingSuffix = props.getString("greeting.suffix", "");
        LogLevel level = props.getEnum("log.level", LogLevel.class, LogLevel.INFO);
        List<String> tags = props.getList("tags");
        System.out.println("[HelloPlugin] onInitialized | suffix='" + greetingSuffix
                + "' level=" + level + " tags=" + tags);
    }

    @Override
    public void onStart() {
        // 订阅其他插件发布的 GreetingEvent；UNLOADED 时框架自动清理
        subscribe(GreetingEvent.class, event -> System.out.println(
                "[HelloPlugin] 收到来自 " + event.getSourcePluginId()
                        + " 的问候: " + event.getMessage()));
        System.out.println("[HelloPlugin] onStart | 已订阅 GreetingEvent");
    }

    @Override
    public void onStop() {
        System.out.println("[HelloPlugin] onStop");
    }

    /** 配置使用枚举的小示例。 */
    public enum LogLevel { DEBUG, INFO, WARN, ERROR }
}

@Extension(order = 10, description = "英语问候")
class EnglishGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name + "!" + HelloPlugin.greetingSuffix;
    }
}
