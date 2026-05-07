package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.CircularDependencyException;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.bingbaihanji.sunsen.api.PluginState;
import com.bingbaihanji.sunsen.core.test.ext.GreetingExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultPluginManager} 集成测试
 */
public class DefaultPluginManagerIT {

    /**
     * JUnit 临时目录
     */
    @TempDir
    static Path tempDir;

    /**
     * 测试插件目录
     */
    static Path pluginsDir;
    /**
     * Hello 测试插件 JAR 路径
     */
    static Path helloJar;
    /**
     * World 测试插件 JAR 路径
     */
    static Path worldJar;

    @BeforeAll
    static void setUpAll() throws Exception {
        pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        Path srcDir = Path.of("src/test/resources/test-plugins");
        helloJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-hello"), pluginsDir);
        worldJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-world"), pluginsDir);
    }

    @BeforeEach
    void cleanPluginsDir() throws Exception {
        // 清空插件目录,只保留需要的 JAR
        try (var stream = Files.list(pluginsDir)) {
            stream.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
        Files.copy(helloJar, pluginsDir.resolve("plugin-hello.jar"));
        Files.copy(worldJar, pluginsDir.resolve("plugin-world.jar"));
    }

    @Test
    void testLoadAndStartPlugins() {
        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        manager.loadPlugins();
        assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));

        manager.startPlugins();
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));

        List<GreetingExtension> extensions = manager.getExtensions(GreetingExtension.class);
        assertEquals(2, extensions.size());
        assertEquals("Hello", extensions.get(0).greet());
        assertEquals("World", extensions.get(1).greet());
    }

    @Test
    void testStopAndUnloadPlugins() {
        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);
        manager.loadPlugins();
        manager.startPlugins();

        manager.stopPlugins();
        assertEquals(PluginState.STOPPED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertEquals(PluginState.STOPPED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));

        manager.unloadPlugins();
        assertEquals(PluginState.UNLOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertEquals(PluginState.UNLOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));

        List<GreetingExtension> extensions = manager.getExtensions(GreetingExtension.class);
        assertTrue(extensions.isEmpty());
    }

    @Test
    void testMissingDependency() throws Exception {
        Files.deleteIfExists(pluginsDir.resolve("plugin-hello.jar"));
        Files.deleteIfExists(pluginsDir.resolve("plugin-world.jar"));

        Path missingJar = TestPluginPackager.packagePlugin(
                Path.of("src/test/resources/test-plugins/plugin-world-missing-dep"), pluginsDir);
        Files.copy(missingJar, pluginsDir.resolve("plugin-missing.jar"));

        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        assertThrows(PluginLoadException.class, manager::loadPlugins);
    }

    @Test
    void testCircularDependency() throws Exception {
        Files.deleteIfExists(pluginsDir.resolve("plugin-hello.jar"));
        Files.deleteIfExists(pluginsDir.resolve("plugin-world.jar"));

        Path srcDir = Path.of("src/test/resources/test-plugins");
        Path aJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-a-circular"), pluginsDir);
        Path bJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-b-circular"), pluginsDir);
        Files.copy(aJar, pluginsDir.resolve("plugin-a.jar"));
        Files.copy(bJar, pluginsDir.resolve("plugin-b.jar"));

        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        assertThrows(CircularDependencyException.class, manager::loadPlugins);
    }
}
