package com.bingbaihanji.sunsen.demo.plain.world;

import com.bingbaihanji.sunsen.api.PluginState;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.api.annotation.Plugin;
import com.bingbaihanji.sunsen.api.support.AbstractPlugin;
import com.bingbaihanji.sunsen.demo.plain.Greeter;
import com.bingbaihanji.sunsen.demo.plain.GreetingFormatter;

import java.util.Optional;

/**
 * World 演示插件
 * <p>
 * 高级用法演示：
 * <ul>
 *   <li>用 {@code getPluginState} 检查依赖插件运行状态（声明依赖 {@code hello}）</li>
 *   <li>用 {@code firstExtension(GreetingFormatter.class)} 拿到全局优先级最高的格式化器</li>
 *   <li>{@code onStart()} 内访问扩展是安全的：所有插件已 LOADED，扩展全部注册完毕</li>
 * </ul>
 * <p>
 * 本插件只依赖 demo-api（{@link Greeter}、{@link GreetingFormatter}），不依赖 demo-host。
 */
@Plugin(
        id = "com.bingbaihanji.sunsen.demo.plain.world",
        name = "World Plugin",
        version = "1.0.0",
        packagePrefixes = "com.bingbaihanji.sunsen.demo.plain.world",
        dependencies = @Plugin.Dependency(
                id = "com.bingbaihanji.sunsen.demo.plain.hello",
                version = ">=1.0.0"
        )
)
public class WorldPlugin extends AbstractPlugin {

    @Override
    protected void onInitialized() {
        System.out.println("[WorldPlugin] onInitialized");
    }

    @Override
    public void onStart() {
        // 此时所有插件已经 LOADED，依赖插件状态可以查询
        PluginState helloState = getPluginState("com.bingbaihanji.sunsen.demo.plain.hello");
        System.out.println("[WorldPlugin] onStart | hello 插件状态=" + helloState);

        // 跨插件查询扩展：所有 LOADED 的插件贡献的扩展都已注册
        Optional<GreetingFormatter> formatter = firstExtension(GreetingFormatter.class);
        formatter.ifPresent(f -> System.out.println(
                "[WorldPlugin] 发现格式化器: " + f.format("Hi")));
    }

    @Override
    public void onStop() {
        System.out.println("[WorldPlugin] onStop");
    }
}

@Extension(order = 2, description = "中文问候")
class ChineseGreeterRude implements Greeter {
    @Override
    public String greet(String name) {
        return "小逼崽子" + name + "你好！";
    }
}

@Extension(order = 2, description = "中文问候")
class ChineseGreeterGent implements Greeter {
    @Override
    public String greet(String name) {
        return name + "先生" + "你好！";
    }
}
