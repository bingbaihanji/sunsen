package com.bingbaihanji.sunsen.demo.plain;

import com.bingbaihanji.sunsen.api.ExtensionRegistrar;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.builtin.PluginLoadedEvent;
import com.bingbaihanji.sunsen.api.event.builtin.PluginStartedEvent;
import com.bingbaihanji.sunsen.api.event.builtin.PluginStoppedEvent;
import com.bingbaihanji.sunsen.api.event.builtin.PluginUnloadedEvent;
import com.bingbaihanji.sunsen.core.DefaultPluginManager;
import com.bingbaihanji.sunsen.demo.plain.event.GreetingEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Sunsen 纯 Java 宿主演示程序
 * <p>
 * 演示进阶用法：
 * <ul>
 *   <li>{@link ExtensionRegistrar} 钩子，把扩展生命周期同步到宿主侧</li>
 *   <li>父类型事件订阅：监听 {@code PluginEvent.class} 收到所有生命周期与业务事件</li>
 *   <li>{@code allowMultiple = false} 的 {@link GreetingFormatter} 单例扩展点</li>
 *   <li>插件自定义事件 {@link GreetingEvent}：scheduler 发布、hello 订阅、宿主旁观</li>
 *   <li>{@code ManagedScheduler} 后台定时器：等待 3 秒观察周期性 tick</li>
 * </ul>
 */
public class PlainDemoApp {

    public static void main(String[] args) throws InterruptedException {
        Path pluginsDir = Path.of("sunsen-demo-plain/target/plugins");
        if (!java.nio.file.Files.exists(pluginsDir)) {
            // 兼容在模块根目录运行
            pluginsDir = Path.of("target/plugins");
        }
        pluginsDir = pluginsDir.toAbsolutePath().normalize();

        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        // 1. 注册 ExtensionRegistrar：扩展实例创建/销毁时由框架回调
        manager.setExtensionRegistrar(new ExtensionRegistrar() {
            @Override
            public void afterExtensionCreated(Object instance, Class<?> extensionPoint) {
                System.out.println("    [Registrar] + " + extensionPoint.getSimpleName()
                        + " <- " + instance.getClass().getSimpleName());
            }

            @Override
            public void afterExtensionDestroyed(Object instance, Class<?> extensionPoint) {
                System.out.println("    [Registrar] - " + extensionPoint.getSimpleName()
                        + " <- " + instance.getClass().getSimpleName());
            }
        });

        // 2. 订阅父类型 PluginEvent：接收所有生命周期事件 + 插件发布的业务事件
        manager.subscribe(PluginEvent.class, event -> {
            String tag = switch (event) {
                case PluginLoadedEvent e -> "LOADED";
                case PluginStartedEvent e -> "STARTED";
                case PluginStoppedEvent e -> "STOPPED";
                case PluginUnloadedEvent e -> "UNLOADED";
                case GreetingEvent e -> "GREET '" + e.getMessage() + "'";
                default -> event.getClass().getSimpleName();
            };
            System.out.println("    [Bus] " + tag + " from " + event.getSourcePluginId());
        });

        System.out.println("=== Sunsen Plain Demo ===");
        System.out.println("插件目录: " + pluginsDir);

        // 3. 加载插件（依赖拓扑排序）
        System.out.println("\n--- 1) loadPlugins() ---");
        manager.loadPlugins();
        List<PluginDescriptor> plugins = manager.getPlugins();
        System.out.println("已加载插件数量: " + plugins.size());
        for (PluginDescriptor pd : plugins) {
            System.out.println("  - " + pd.id() + " [" + pd.version() + "] perms=" + pd.permissions());
        }

        // 4. 启动插件（拓扑顺序，onStart 中可访问扩展）
        System.out.println("\n--- 2) startPlugins() ---");
        manager.startPlugins();

        // 5. 调用所有 Greeter 扩展（allowMultiple = true，按 order 升序）
        System.out.println("\n--- 3) Greeter 扩展遍历 ---");
        List<Greeter> greeters = manager.getExtensions(Greeter.class);
        System.out.println("发现 Greeter 扩展数量: " + greeters.size());
        for (Greeter greeter : greeters) {
            System.out.println("  -> " + greeter.greet("Sunsen"));
        }

        // 6. 单例扩展 GreetingFormatter (allowMultiple = false)
        System.out.println("\n--- 4) GreetingFormatter 单例扩展 ---");
        List<GreetingFormatter> formatters = manager.getExtensions(GreetingFormatter.class);
        Optional<GreetingFormatter> formatter = formatters.stream().findFirst();
        if (formatter.isPresent() && !greeters.isEmpty()) {
            String raw = greeters.getFirst().greet("Sunsen");
            System.out.println("  原文: " + raw);
            System.out.println("  装饰: " + formatter.get().format(raw));
        }

        // 7. 等待 3 秒观察 SchedulerPlugin 的定时 tick → GreetingEvent → HelloPlugin 接收
        System.out.println("\n--- 5) 等待 3 秒观察 scheduler tick ---");
        Thread.sleep(3000);

        // 8. 停止 + 卸载
        System.out.println("\n--- 6) stopPlugins() ---");
        manager.stopPlugins();

        System.out.println("\n--- 7) unloadPlugins() ---");
        manager.unloadPlugins();

        System.out.println("\n=== 演示结束 ===");
    }
}
