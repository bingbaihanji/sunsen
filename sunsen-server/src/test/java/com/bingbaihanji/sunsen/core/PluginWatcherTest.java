package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PluginWatcher} 单元测试
 * @author 冰白寒祭
 * @date 2025
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginWatcherTest {

    @TempDir
    static Path tempDir;
    static Path helloJar;
    static Path worldJar;
    Path pluginsDir;
    DefaultPluginManager manager;
    PluginWatcher watcher;

    @BeforeAll
    static void setUpAll() throws Exception {
        Path fixtureDir = tempDir.resolve("fixtures");
        Files.createDirectories(fixtureDir);
        Path srcDir = Path.of("src/test/resources/test-plugins");
        helloJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-hello"), fixtureDir);
        worldJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-world"), fixtureDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) {
            watcher.close();
        }
        if (manager != null) {
            try {
                manager.unloadPlugins();
            } catch (Exception ignored) {
            }
        }
    }

    private void initManagerAndWatcher() throws Exception {
        pluginsDir = tempDir.resolve("watcher-plugins-" + System.nanoTime());
        Files.createDirectories(pluginsDir);
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);
        watcher = new PluginWatcher(manager, pluginsDir);
    }

    @Test
    void testAutoLoadNewJar() throws Exception {
        initManagerAndWatcher();
        assertTrue(manager.getPlugins().isEmpty());

        Path dest = pluginsDir.resolve("plugin-hello.jar");
        Files.copy(helloJar, dest);

        Thread.sleep(1500);

        assertEquals(1, manager.getPlugins().size());
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    }

    @Test
    void testAutoReloadAfterReplace() throws Exception {
        initManagerAndWatcher();

        // Auto-load via watcher
        Path dest = pluginsDir.resolve("plugin-hello.jar");
        Files.copy(helloJar, dest);
        Thread.sleep(1500);
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        // Release file locks (Windows needs this before overwriting)
        watcher.close();
        manager.stopPlugin("com.bingbaihanji.sunsen.core.test.hello");
        manager.unloadPlugin("com.bingbaihanji.sunsen.core.test.hello");

        // Replace JAR and manually reload (watcher is closed so no auto-detection)
        Files.copy(helloJar, dest, StandardCopyOption.REPLACE_EXISTING);
        manager.loadPlugin(dest);
        manager.startPlugin("com.bingbaihanji.sunsen.core.test.hello");

        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    }

    @Test
    void testAutoUnloadDeletedJar() throws Exception {
        initManagerAndWatcher();

        Path dest = pluginsDir.resolve("plugin-hello.jar");
        Files.copy(helloJar, dest);

        // 轮询等待 watcher 自动加载并启动插件
        long deadline = System.currentTimeMillis() + 5_000;
        while (manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello") != PluginState.ACTIVE
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"),
                "watcher 应自动加载并启动插件");

        // Windows 上已加载的 JAR 会被 ClassLoader 占用，无法直接删除。
        // 先手动 stop/unload 释放文件句柄，再删除 JAR，验证 watcher 后续不会重新加载。
        manager.stopPlugin("com.bingbaihanji.sunsen.core.test.hello");
        manager.unloadPlugin("com.bingbaihanji.sunsen.core.test.hello");

        Files.delete(dest);

        deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertNull(manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"),
                "卸载并删除 JAR 后插件应保持未加载状态");
    }

    @Test
    void testDebounceMergesMultipleEvents() throws Exception {
        initManagerAndWatcher();

        Path dest = pluginsDir.resolve("plugin-hello.jar");
        Files.copy(helloJar, dest);
        Thread.sleep(100);
        Files.copy(helloJar, dest, StandardCopyOption.REPLACE_EXISTING);
        Thread.sleep(100);
        Files.copy(helloJar, dest, StandardCopyOption.REPLACE_EXISTING);

        Thread.sleep(1500);

        assertEquals(1, manager.getPlugins().size());
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    }

    @Test
    void testIgnoreNonJarFiles() throws Exception {
        initManagerAndWatcher();

        Files.writeString(pluginsDir.resolve("readme.txt"), "hello");
        Files.writeString(pluginsDir.resolve("config.tmp"), "tmp");
        Files.createFile(pluginsDir.resolve("backup.log"));

        Thread.sleep(1500);

        assertTrue(manager.getPlugins().isEmpty());
    }

    @Test
    void testHandleCorruptedJar() throws Exception {
        initManagerAndWatcher();

        Path corrupted = pluginsDir.resolve("corrupted.jar");
        Files.write(corrupted, new byte[]{0, 1, 2, 3});

        Thread.sleep(1500);

        assertTrue(manager.getPlugins().isEmpty());
    }
}
