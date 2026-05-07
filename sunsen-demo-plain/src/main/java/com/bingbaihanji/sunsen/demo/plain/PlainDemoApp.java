package com.bingbaihanji.sunsen.demo.plain;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.core.DefaultPluginManager;

import java.nio.file.Path;
import java.util.List;

/**
 * Sunsen 纯 Java 宿主演示程序
 */
public class PlainDemoApp {

    public static void main(String[] args) {
        Path pluginsDir = Path.of("sunsen-demo-plain/target/plugins");
        if (!java.nio.file.Files.exists(pluginsDir)) {
            // 从当前工作目录向上查找(兼容在模块根目录运行)
            pluginsDir = Path.of("target/plugins");
        }

        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir.toAbsolutePath().normalize());

        System.out.println("=== Sunsen Plain Demo ===");
        System.out.println("插件目录: " + pluginsDir);

        // 加载插件
        manager.loadPlugins();
        List<PluginDescriptor> plugins = manager.getPlugins();
        System.out.println("已加载插件数量: " + plugins.size());
        for (PluginDescriptor pd : plugins) {
            System.out.println("  - " + pd.id() + " [" + pd.version() + "]");
        }

        // 启动插件
        manager.startPlugins();
        System.out.println("插件已启动");

        // 调用扩展
        List<Greeter> greeters = manager.getExtensions(Greeter.class);
        System.out.println("发现 Greeter 扩展数量: " + greeters.size());
        for (Greeter greeter : greeters) {
            System.out.println("  -> " + greeter.greet("Sunsen"));
        }

        // 停止并卸载
        manager.stopPlugins();
        manager.unloadPlugins();
        System.out.println("插件已卸载,演示结束");
    }
}
