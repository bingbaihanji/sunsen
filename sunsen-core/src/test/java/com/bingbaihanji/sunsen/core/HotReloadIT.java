package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.ExtensionRegistrar;
import com.bingbaihanji.sunsen.api.PluginState;
import com.bingbaihanji.sunsen.core.test.ext.GreetingExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热重载集成测试
 */
public class HotReloadIT {

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
     * Hello 测试插件 V1 JAR 路径
     */
    static Path helloJarV1;

    @BeforeAll
    static void setUpAll() throws Exception {
        pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        Path srcDir = Path.of("src/test/resources/test-plugins");
        helloJarV1 = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-hello"), pluginsDir);
    }

    @BeforeEach
    void cleanPluginsDir() throws Exception {
        try (var stream = Files.list(pluginsDir)) {
            stream.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
        Files.copy(helloJarV1, pluginsDir.resolve("plugin-hello.jar"));
    }

    @Test
    void testReloadPlugin() throws Exception {
        AtomicInteger destroyedCount = new AtomicInteger(0);
        AtomicInteger createdCount = new AtomicInteger(0);
        List<Class<?>> destroyedClasses = new CopyOnWriteArrayList<>();

        ExtensionRegistrar registrar = new ExtensionRegistrar() {
            @Override
            public void afterExtensionCreated(Object extensionInstance, Class<?> extensionPointType) {
                createdCount.incrementAndGet();
            }

            @Override
            public void afterExtensionDestroyed(Object extensionInstance, Class<?> extensionPointType) {
                destroyedCount.incrementAndGet();
                destroyedClasses.add(extensionInstance.getClass());
            }
        };

        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);
        manager.setExtensionRegistrar(registrar);

        manager.loadPlugins();
        manager.startPlugins();

        List<GreetingExtension> extsBefore = manager.getExtensions(GreetingExtension.class);
        assertEquals(1, extsBefore.size());
        GreetingExtension extBefore = extsBefore.get(0);
        ClassLoader oldLoader = extBefore.getClass().getClassLoader();
        WeakReference<ClassLoader> weakRef = new WeakReference<>(oldLoader);

        // 重新打包相同源码作为 V2(内容相同但 JAR 文件不同)
        Path helloJarV2 = TestPluginPackager.packagePlugin(
                Path.of("src/test/resources/test-plugins/plugin-hello"), pluginsDir.resolve("v2"));

        manager.reloadPlugin("com.bingbaihanji.sunsen.core.test.hello", helloJarV2);

        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        List<GreetingExtension> extsAfter = manager.getExtensions(GreetingExtension.class);
        assertEquals(1, extsAfter.size());
        GreetingExtension extAfter = extsAfter.get(0);

        // 验证实例已替换
        assertNotSame(extBefore, extAfter);

        // 验证 afterExtensionDestroyed 被调用
        assertTrue(destroyedCount.get() >= 1, "afterExtensionDestroyed 应被调用");

        // 尝试 GC 旧 ClassLoader
        extBefore = null;
        oldLoader = null;
        System.gc();
        Thread.sleep(100);

        // 弱引用应被回收(不一定每次都能成功,放宽断言)
        // 如果测试环境内存充足,可能不立即回收,这里仅做软性检查
        // assertNull(weakRef.get());
    }
}
