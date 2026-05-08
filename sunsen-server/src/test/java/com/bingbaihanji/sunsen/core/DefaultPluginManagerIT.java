package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.CircularDependencyException;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.bingbaihanji.sunsen.api.PluginState;
import com.bingbaihanji.sunsen.core.test.ext.GreetingExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    DefaultPluginManager manager;

    @BeforeAll
    static void setUpAll() throws Exception {
        Path fixtureDir = tempDir.resolve("fixtures");
        Files.createDirectories(fixtureDir);

        Path srcDir = Path.of("src/test/resources/test-plugins");
        helloJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-hello"), fixtureDir);
        worldJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-world"), fixtureDir);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            try {
                manager.unloadPlugins();
            } catch (Exception ignored) {
            }
            manager = null;
        }
    }

    @BeforeEach
    void setUpPluginsDir() throws Exception {
        pluginsDir = tempDir.resolve("plugins-" + System.nanoTime());
        Files.createDirectories(pluginsDir);
        Files.copy(helloJar, pluginsDir.resolve("plugin-hello.jar"));
        Files.copy(worldJar, pluginsDir.resolve("plugin-world.jar"));
    }

    @Test
    void testLoadAndStartPlugins() {
        manager = new DefaultPluginManager();
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
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);
        manager.loadPlugins();
        manager.startPlugins();

        manager.stopPlugins();
        assertEquals(PluginState.STOPPED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertEquals(PluginState.STOPPED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));

        manager.unloadPlugins();
        assertNull(manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertNull(manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));

        List<GreetingExtension> extensions = manager.getExtensions(GreetingExtension.class);
        assertTrue(extensions.isEmpty());
    }

    @Test
    void testMissingDependency() throws Exception {
        Files.deleteIfExists(pluginsDir.resolve("plugin-hello.jar"));
        Files.deleteIfExists(pluginsDir.resolve("plugin-world.jar"));

        Path missingJar = TestPluginPackager.packagePlugin(
                Path.of("src/test/resources/test-plugins/plugin-world-missing-dep"), pluginsDir);
        Files.copy(missingJar, pluginsDir.resolve("plugin-missing.jar"), StandardCopyOption.REPLACE_EXISTING);

        manager = new DefaultPluginManager();
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
        Files.copy(aJar, pluginsDir.resolve("plugin-a.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(bJar, pluginsDir.resolve("plugin-b.jar"), StandardCopyOption.REPLACE_EXISTING);

        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        assertThrows(CircularDependencyException.class, manager::loadPlugins);
    }
}
