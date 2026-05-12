package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.ExtensionInfo;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionRegistryTest {

    ExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry();
    }

    // ---- 测试用扩展点和扩展类 ----

    @ExtensionPoint
    interface Greeter {
        String greet();
    }

    @Extension(id = "hello", order = 10)
    static class HelloGreeter implements Greeter {
        @Override public String greet() { return "hello"; }
    }

    @Extension(id = "world", order = 20)
    static class WorldGreeter implements Greeter {
        @Override public String greet() { return "world"; }
    }

    @Extension(id = "hi", order = 5)
    static class HiGreeter implements Greeter {
        @Override public String greet() { return "hi"; }
    }

    @Extension(id = "proto", singleton = false, order = 0)
    static class ProtoGreeter implements Greeter {
        @Override public String greet() { return "proto"; }
    }

    @ExtensionPoint(sole = true)
    interface Backend {
        String name();
    }

    @Extension(id = "backend-a", order = 10)
    static class BackendA implements Backend {
        @Override public String name() { return "A"; }
    }

    @Extension(id = "backend-b", order = 20)
    static class BackendB implements Backend {
        @Override public String name() { return "B"; }
    }

    // ---- 辅助方法 ----

    private PluginDescriptor descriptor(String id) {
        return new PluginDescriptor(id, id, "", "1.0.0", "1.0", "Main",
                List.of("com.test." + id), List.of(), Set.of(), Map.of());
    }

    // ---- 基础注册 ----

    @Test
    void testEmptyRegistryReturnsEmptyList() {
        assertTrue(registry.getExtensions(Greeter.class).isEmpty());
    }

    @Test
    void testRegisterAndRetrieveInOrderAscending() {
        PluginDescriptor d = descriptor("plugin-a");
        registry.register(d, HelloGreeter.class, Greeter.class); // order=10
        registry.register(d, WorldGreeter.class, Greeter.class); // order=20
        registry.register(d, HiGreeter.class, Greeter.class);    // order=5

        List<Greeter> greeters = registry.getExtensions(Greeter.class);
        assertEquals(3, greeters.size());
        assertEquals("hi",    greeters.get(0).greet()); // order 5
        assertEquals("hello", greeters.get(1).greet()); // order 10
        assertEquals("world", greeters.get(2).greet()); // order 20
    }

    @Test
    void testGetExtensionById() {
        PluginDescriptor d = descriptor("plugin-a");
        registry.register(d, HelloGreeter.class, Greeter.class);
        registry.register(d, WorldGreeter.class, Greeter.class);

        Optional<Greeter> found = registry.getExtension(Greeter.class, "hello");
        assertTrue(found.isPresent());
        assertEquals("hello", found.get().greet());

        assertTrue(registry.getExtension(Greeter.class, "missing").isEmpty());
    }

    // ---- 单例 vs 原型 ----

    @Test
    void testSingletonReturnsSameInstance() {
        registry.register(descriptor("plugin-a"), HelloGreeter.class, Greeter.class);
        Greeter g1 = registry.getExtensions(Greeter.class).get(0);
        Greeter g2 = registry.getExtensions(Greeter.class).get(0);
        assertSame(g1, g2);
    }

    @Test
    void testPrototypeReturnsNewInstanceEachTime() {
        registry.register(descriptor("plugin-a"), ProtoGreeter.class, Greeter.class);
        Greeter g1 = registry.getExtensions(Greeter.class).get(0);
        Greeter g2 = registry.getExtensions(Greeter.class).get(0);
        assertNotSame(g1, g2);
        assertEquals("proto", g1.greet());
    }

    // ---- 注销 ----

    @Test
    void testUnregisterRemovesAllExtensionsOfPlugin() {
        PluginDescriptor d = descriptor("plugin-a");
        registry.register(d, HelloGreeter.class, Greeter.class);
        registry.register(d, WorldGreeter.class, Greeter.class);
        assertEquals(2, registry.getExtensions(Greeter.class).size());

        registry.unregisterPlugin("plugin-a");
        assertTrue(registry.getExtensions(Greeter.class).isEmpty());
    }

    @Test
    void testUnregisterOnlyAffectsTargetPlugin() {
        registry.register(descriptor("plugin-a"), HelloGreeter.class, Greeter.class);
        registry.register(descriptor("plugin-b"), WorldGreeter.class, Greeter.class);

        registry.unregisterPlugin("plugin-a");

        List<Greeter> remaining = registry.getExtensions(Greeter.class);
        assertEquals(1, remaining.size());
        assertEquals("world", remaining.get(0).greet());
    }

    // ---- sole 扩展点 ----

    @Test
    void testSoleLowerOrderWins() {
        // BackendA order=10 注册在前，BackendB order=20 注册在后 → A 胜
        registry.register(descriptor("plugin-a"), BackendA.class, Backend.class);
        registry.register(descriptor("plugin-b"), BackendB.class, Backend.class);

        List<Backend> backends = registry.getExtensions(Backend.class);
        assertEquals(1, backends.size());
        assertEquals("A", backends.get(0).name());
    }

    @Test
    void testSoleHigherPriorityWinsWhenRegisteredSecond() {
        // BackendB order=20 注册在前，BackendA order=10 注册在后 → A 仍胜
        registry.register(descriptor("plugin-b"), BackendB.class, Backend.class);
        registry.register(descriptor("plugin-a"), BackendA.class, Backend.class);

        List<Backend> backends = registry.getExtensions(Backend.class);
        assertEquals(1, backends.size());
        assertEquals("A", backends.get(0).name());
    }

    @Test
    void testSoleLoserRestoredAfterWinnerUnloaded() {
        registry.register(descriptor("plugin-a"), BackendA.class, Backend.class); // 胜 order=10
        registry.register(descriptor("plugin-b"), BackendB.class, Backend.class); // 败 order=20

        // 卸载胜者后，BackendB 应自动恢复
        registry.unregisterPlugin("plugin-a");

        List<Backend> backends = registry.getExtensions(Backend.class);
        assertEquals(1, backends.size());
        assertEquals("B", backends.get(0).name());
    }

    @Test
    void testSoleNoRestorationIfLoserAlsoUnloaded() {
        registry.register(descriptor("plugin-a"), BackendA.class, Backend.class);
        registry.register(descriptor("plugin-b"), BackendB.class, Backend.class);

        registry.unregisterPlugin("plugin-b"); // 先卸载败者（从候选列表移除）
        registry.unregisterPlugin("plugin-a"); // 再卸载胜者，无候选可恢复

        assertTrue(registry.getExtensions(Backend.class).isEmpty());
    }

    // ---- 元数据 ----

    @Test
    void testGetExtensionInfos() {
        registry.register(descriptor("plugin-a"), HelloGreeter.class, Greeter.class);
        List<ExtensionInfo> infos = registry.getExtensionInfos(Greeter.class);
        assertEquals(1, infos.size());
        ExtensionInfo info = infos.get(0);
        assertEquals("hello", info.extensionId());
        assertEquals(10, info.order());
        assertEquals("plugin-a", info.pluginId());
        assertTrue(info.singleton());
        assertEquals(Greeter.class, info.extensionPointType());
    }

    @Test
    void testGetExtensionInfosEmpty() {
        assertTrue(registry.getExtensionInfos(Greeter.class).isEmpty());
    }

    // ---- withWriteLock ----

    @Test
    void testWithWriteLockExecutesAction() {
        registry.withWriteLock(() ->
                registry.register(descriptor("plugin-a"), HelloGreeter.class, Greeter.class));
        assertEquals(1, registry.getExtensions(Greeter.class).size());
    }

    // ---- @Extension 未标注时应抛出 ----

    static class NotAnnotated implements Greeter {
        @Override public String greet() { return ""; }
    }

    @Test
    void testRegisterWithoutExtensionAnnotationThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(descriptor("plugin-a"), NotAnnotated.class, Greeter.class));
    }
}
