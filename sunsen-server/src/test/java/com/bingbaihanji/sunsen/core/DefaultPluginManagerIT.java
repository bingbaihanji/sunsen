package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.*;
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
import java.util.Map;
import java.util.Set;

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
    /**
     * Broken 测试插件 JAR 路径
     */
    static Path brokenJar;

    DefaultPluginManager manager;

    @BeforeAll
    static void setUpAll() throws Exception {
        Path fixtureDir = tempDir.resolve("fixtures");
        Files.createDirectories(fixtureDir);

        Path srcDir = Path.of("src/test/resources/test-plugins");
        helloJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-hello"), fixtureDir);
        worldJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-world"), fixtureDir);
        brokenJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-broken"), fixtureDir);
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

        // Package to a separate staging dir so pluginsDir contains only the one renamed JAR
        Path stagingDir = tempDir.resolve("fixtures-missing-dep");
        Files.createDirectories(stagingDir);
        Path missingJar = TestPluginPackager.packagePlugin(
                Path.of("src/test/resources/test-plugins/plugin-world-missing-dep"), stagingDir);
        Files.copy(missingJar, pluginsDir.resolve("plugin-missing.jar"), StandardCopyOption.REPLACE_EXISTING);

        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        assertThrows(PluginLoadException.class, manager::loadPlugins);
    }

    @Test
    void testCircularDependency() throws Exception {
        Files.deleteIfExists(pluginsDir.resolve("plugin-hello.jar"));
        Files.deleteIfExists(pluginsDir.resolve("plugin-world.jar"));

        // Package to a separate staging dir so pluginsDir contains only the two renamed JARs
        Path stagingDir = tempDir.resolve("fixtures-circular");
        Files.createDirectories(stagingDir);
        Path srcDir = Path.of("src/test/resources/test-plugins");
        Path aJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-a-circular"), stagingDir);
        Path bJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-b-circular"), stagingDir);
        Files.copy(aJar, pluginsDir.resolve("plugin-a.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(bJar, pluginsDir.resolve("plugin-b.jar"), StandardCopyOption.REPLACE_EXISTING);

        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        assertThrows(CircularDependencyException.class, manager::loadPlugins);
    }

    @Test
    void testInstallPlugin() throws Exception {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        Path externalJar = tempDir.resolve("external-plugins").resolve("plugin-external.jar");
        Files.createDirectories(externalJar.getParent());
        Files.copy(helloJar, externalJar);

        manager.installPlugin(externalJar);

        Path installedPath = pluginsDir.resolve("plugin-external.jar");
        assertTrue(Files.exists(installedPath));
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    }

    @Test
    void testInstallPluginSourceNotExist() {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        Path nonexistent = tempDir.resolve("nonexistent.jar");
        assertThrows(PluginLoadException.class, () -> manager.installPlugin(nonexistent));
        assertFalse(manager.getPlugin("com.bingbaihanji.sunsen.core.test.hello").isPresent());
    }

    @Test
    void testInstallRollbackOnStartFailure() throws Exception {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        Path externalJar = tempDir.resolve("staging").resolve("plugin-external.jar");
        Files.createDirectories(externalJar.getParent());
        Files.copy(brokenJar, externalJar);

        assertThrows(PluginLoadException.class, () -> manager.installPlugin(externalJar));

        Path copiedPath = pluginsDir.resolve("plugin-external.jar");
        assertFalse(Files.exists(copiedPath), "copied JAR should be cleaned on failure");
        assertFalse(manager.getPlugin("com.bingbaihanji.sunsen.core.test.hello").isPresent());
    }

    @Test
    void testUninstallPlugin() throws Exception {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        Path jarInDir = pluginsDir.resolve("plugin-hello.jar");
        assertTrue(Files.exists(jarInDir));

        manager.loadPlugin(jarInDir);
        manager.startPlugin("com.bingbaihanji.sunsen.core.test.hello");
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        manager.uninstallPlugin("com.bingbaihanji.sunsen.core.test.hello");

        assertFalse(Files.exists(jarInDir), "JAR file should be deleted");
        assertFalse(manager.getPlugin("com.bingbaihanji.sunsen.core.test.hello").isPresent());
    }

    @Test
    void testLoadPluginsWithResult() throws Exception {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        Path corrupted = pluginsDir.resolve("corrupted.jar");
        Files.write(corrupted, new byte[]{0, 1, 2, 3});

        var errors = manager.loadPluginsWithResult();
        assertFalse(errors.isEmpty(), "should have at least one error for the corrupted JAR");
        assertEquals(2, manager.getPlugins().size(), "two valid plugins should still load");
        assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));
    }

    @Test
    void testLoadPluginsIncrementallyUsesAlreadyLoadedDependencies() throws Exception {
        Files.deleteIfExists(pluginsDir.resolve("plugin-world.jar"));

        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        manager.loadPlugins();
        assertEquals(1, manager.getPlugins().size());
        assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        Files.copy(worldJar, pluginsDir.resolve("plugin-world.jar"), StandardCopyOption.REPLACE_EXISTING);

        manager.loadPlugins();

        assertEquals(2, manager.getPlugins().size());
        assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));
    }

    @Test
    void testPluginManagerViewRejectsHostManagementOperations() {
        PluginDescriptor descriptor = new PluginDescriptor(
                "plugin-view-test",
                "Plugin View Test",
                "",
                "1.0.0",
                "1.0",
                "example.Plugin",
                List.of("example"),
                List.of(),
                Set.of(),
                Map.of()
        );
        DefaultPluginContext context = new DefaultPluginContext(
                descriptor,
                new ExtensionRegistry(),
                new PluginEventBus(),
                new DefaultPluginManager(),
                pluginsDir.resolve("plugin-view-test"),
                new ThreadGroup("plugin-view-test")
        );

        assertThrows(PluginPermissionException.class, () -> context.getPluginManager().unloadPlugins());
        assertThrows(PluginPermissionException.class, () -> context.getPluginManager().installPlugin(helloJar));
        assertDoesNotThrow(() -> context.getPluginManager().getPlugins());
    }

    @Test
    void testReloadPluginWithRollback() throws Exception {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        Path jarFile = pluginsDir.resolve("plugin-hello.jar");
        manager.loadPlugin(jarFile);
        manager.startPlugin("com.bingbaihanji.sunsen.core.test.hello");
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        assertThrows(PluginLoadException.class,
                () -> manager.reloadPlugin("com.bingbaihanji.sunsen.core.test.hello", brokenJar));

        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertTrue(manager.getPlugin("com.bingbaihanji.sunsen.core.test.hello").isPresent());
    }

    @Test
    void testConcurrentWaveLoading() {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        manager.loadPlugins();
        assertEquals(2, manager.getPlugins().size());
        assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
        assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));
    }

    @Test
    void testStopAndRestartCycle() {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);

        manager.loadPlugins();
        manager.startPlugins();
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        manager.stopPlugins();
        assertEquals(PluginState.STOPPED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        manager.startPlugin("com.bingbaihanji.sunsen.core.test.hello");
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        List<GreetingExtension> ext = manager.getExtensions(GreetingExtension.class);
        assertFalse(ext.isEmpty());
    }
}
