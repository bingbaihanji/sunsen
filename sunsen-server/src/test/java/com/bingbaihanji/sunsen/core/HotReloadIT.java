package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.ExtensionRegistrar;
import com.bingbaihanji.sunsen.api.PluginState;
import com.bingbaihanji.sunsen.core.test.ext.GreetingExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热重载集成测试
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class HotReloadIT {

    /**
     * JUnit 临时目录
     */
    @TempDir
    static Path tempDir;

    /**
     * 测试插件目录
     */
    static Path helloJarV1;

    Path pluginsDir;
    DefaultPluginManager manager;

    @BeforeAll
    static void setUpAll() throws Exception {
        Path fixtureDir = tempDir.resolve("fixtures");
        Files.createDirectories(fixtureDir);

        Path srcDir = Path.of("src/test/resources/test-plugins");
        helloJarV1 = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-hello"), fixtureDir);
    }

    @BeforeEach
    void setUp() throws Exception {
        pluginsDir = tempDir.resolve("plugins-" + System.nanoTime());
        Files.createDirectories(pluginsDir);
        Files.copy(helloJarV1, pluginsDir.resolve("plugin-hello.jar"));
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

        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);
        manager.setExtensionRegistrar(registrar);

        manager.loadPlugins();
        manager.startPlugins();

        List<GreetingExtension> extsBefore = manager.getExtensions(GreetingExtension.class);
        assertEquals(1, extsBefore.size());
        GreetingExtension extBefore = extsBefore.get(0);
        ClassLoader oldLoader = extBefore.getClass().getClassLoader();
        ReferenceQueue<ClassLoader> queue = new ReferenceQueue<>();
        WeakReference<ClassLoader> weakRef = new WeakReference<>(oldLoader, queue);

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

        // 验证旧 JAR 文件句柄已释放:Windows 上若 ClassLoader 未关闭则无法删除
        Path oldJarPath = pluginsDir.resolve("plugin-hello.jar");
        assertDoesNotThrow(() -> Files.delete(oldJarPath), "旧 JAR 文件句柄应在卸载后释放");

        // 清理可能引用旧 Class 的追踪列表,避免妨碍 ClassLoader GC
        destroyedClasses.clear();

        // 最佳努力:先卸载新插件释放文件句柄,再尝试 GC 旧 ClassLoader
        extBefore = null;
        oldLoader = null;
        manager.unloadPlugins();
        manager.setExtensionRegistrar(null);
        manager = null;
        for (int i = 0; i < 20; i++) {
            System.gc();
            if (queue.poll() != null) {
                break;
            }
            Thread.sleep(100);
        }
    }

    @Test
    void testReloadAtomicWindow() throws Exception {
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);
        manager.loadPlugins();
        manager.startPlugins();

        List<GreetingExtension> initial = manager.getExtensions(GreetingExtension.class);
        assertEquals(1, initial.size());
        ClassLoader oldLoader = initial.get(0).getClass().getClassLoader();

        AtomicInteger emptyCount = new AtomicInteger(0);
        AtomicInteger mixedCount = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);

        for (int i = 0; i < 8; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int r = 0; r < 200; r++) {
                        List<GreetingExtension> list = manager.getExtensions(GreetingExtension.class);
                        if (list.isEmpty()) {
                            emptyCount.incrementAndGet();
                        } else if (isMixed(list, oldLoader)) {
                            mixedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        Path helloJarV2 = TestPluginPackager.packagePlugin(
                Path.of("src/test/resources/test-plugins/plugin-hello"), pluginsDir.resolve("v2"));

        start.countDown();
        manager.reloadPlugin("com.bingbaihanji.sunsen.core.test.hello", helloJarV2);
        done.await();

        assertEquals(0, emptyCount.get(), "热重载过程中不应出现扩展空窗期");
        assertEquals(0, mixedCount.get(), "热重载过程中不应同时出现新旧扩展实例");
    }

    private boolean isMixed(List<GreetingExtension> list, ClassLoader oldLoader) {
        boolean hasOld = false;
        boolean hasNew = false;
        for (GreetingExtension ext : list) {
            ClassLoader cl = ext.getClass().getClassLoader();
            if (cl == oldLoader) {
                hasOld = true;
            } else {
                hasNew = true;
            }
            if (hasOld && hasNew) {
                return true;
            }
        }
        return false;
    }
}
