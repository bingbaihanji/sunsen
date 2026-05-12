package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.CircularDependencyException;
import com.bingbaihanji.sunsen.api.DependencyDescriptor;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    DependencyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DependencyResolver();
    }

    // ---- 辅助构造 ----

    private PluginDescriptor plugin(String id, String version, DependencyDescriptor... deps) {
        return new PluginDescriptor(id, id, "", version, "1.0", "Main",
                List.of("com.test." + id), List.of(deps), Set.of(), Map.of());
    }

    private DependencyDescriptor dep(String id, String constraint) {
        return new DependencyDescriptor(id, constraint, false);
    }

    private DependencyDescriptor optDep(String id, String constraint) {
        return new DependencyDescriptor(id, constraint, true);
    }

    private int indexOf(List<PluginDescriptor> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    // ---- 基础排序 ----

    @Test
    void testNoDependenciesResolvesOk() {
        List<PluginDescriptor> plugins = List.of(plugin("a", "1.0.0"), plugin("b", "1.0.0"));
        assertDoesNotThrow(() -> resolver.resolve(plugins));
        List<PluginDescriptor> sorted = resolver.sort(plugins);
        assertEquals(2, sorted.size());
    }

    @Test
    void testLinearDependencyOrder() {
        PluginDescriptor a = plugin("a", "1.0.0");
        PluginDescriptor b = plugin("b", "1.0.0", dep("a", "1.0.0"));
        PluginDescriptor c = plugin("c", "1.0.0", dep("b", "1.0.0"));

        resolver.resolve(List.of(a, b, c));
        List<PluginDescriptor> sorted = resolver.sort(List.of(a, b, c));

        assertEquals(3, sorted.size());
        assertTrue(indexOf(sorted, "a") < indexOf(sorted, "b"));
        assertTrue(indexOf(sorted, "b") < indexOf(sorted, "c"));
    }

    @Test
    void testDiamondDependencyOrder() {
        PluginDescriptor base = plugin("base", "1.0.0");
        PluginDescriptor left = plugin("left", "1.0.0", dep("base", "1.0.0"));
        PluginDescriptor right = plugin("right", "1.0.0", dep("base", "1.0.0"));
        PluginDescriptor top = plugin("top", "1.0.0", dep("left", "1.0.0"), dep("right", "1.0.0"));

        resolver.resolve(List.of(base, left, right, top));
        List<PluginDescriptor> sorted = resolver.sort(List.of(base, left, right, top));

        assertEquals(4, sorted.size());
        assertTrue(indexOf(sorted, "base") < indexOf(sorted, "left"));
        assertTrue(indexOf(sorted, "base") < indexOf(sorted, "right"));
        assertTrue(indexOf(sorted, "left") < indexOf(sorted, "top"));
        assertTrue(indexOf(sorted, "right") < indexOf(sorted, "top"));
    }

    // ---- 缺失依赖 ----

    @Test
    void testMissingRequiredDependencyThrows() {
        PluginDescriptor b = plugin("b", "1.0.0", dep("a", "1.0.0"));
        PluginLoadException ex = assertThrows(PluginLoadException.class,
                () -> resolver.resolve(List.of(b)));
        assertTrue(ex.getMessage().contains("a"));
    }

    @Test
    void testMissingOptionalDependencyOk() {
        PluginDescriptor b = plugin("b", "1.0.0", optDep("a", "1.0.0"));
        assertDoesNotThrow(() -> resolver.resolve(List.of(b)));
    }

    // ---- 版本约束 ----

    @Test
    void testExactVersionMatch() {
        assertDoesNotThrow(() -> resolver.resolve(List.of(
                plugin("a", "1.2.3"),
                plugin("b", "1.0.0", dep("a", "1.2.3"))
        )));
    }

    @Test
    void testExactVersionMismatchThrows() {
        assertThrows(PluginLoadException.class, () -> resolver.resolve(List.of(
                plugin("a", "1.2.4"),
                plugin("b", "1.0.0", dep("a", "1.2.3"))
        )));
    }

    @Test
    void testRangeConstraintSatisfied() {
        assertDoesNotThrow(() -> resolver.resolve(List.of(
                plugin("a", "1.5.0"),
                plugin("b", "1.0.0", dep("a", ">=1.0.0 <2.0.0"))
        )));
    }

    @Test
    void testRangeConstraintUpperBoundViolatedThrows() {
        assertThrows(PluginLoadException.class, () -> resolver.resolve(List.of(
                plugin("a", "2.0.0"),
                plugin("b", "1.0.0", dep("a", ">=1.0.0 <2.0.0"))
        )));
    }

    @Test
    void testCaretConstraintInRange() {
        assertDoesNotThrow(() -> resolver.resolve(List.of(
                plugin("a", "1.9.9"),
                plugin("b", "1.0.0", dep("a", "^1.0.0"))
        )));
    }

    @Test
    void testCaretConstraintOutOfMajorThrows() {
        assertThrows(PluginLoadException.class, () -> resolver.resolve(List.of(
                plugin("a", "2.0.0"),
                plugin("b", "1.0.0", dep("a", "^1.0.0"))
        )));
    }

    @Test
    void testTildeConstraintInRange() {
        assertDoesNotThrow(() -> resolver.resolve(List.of(
                plugin("a", "1.2.9"),
                plugin("b", "1.0.0", dep("a", "~1.2.0"))
        )));
    }

    @Test
    void testTildeConstraintOutOfMinorThrows() {
        assertThrows(PluginLoadException.class, () -> resolver.resolve(List.of(
                plugin("a", "1.3.0"),
                plugin("b", "1.0.0", dep("a", "~1.2.0"))
        )));
    }

    // ---- 循环依赖检测 ----

    @Test
    void testTwoNodeCycleDetected() {
        PluginDescriptor a = plugin("a", "1.0.0", dep("b", "1.0.0"));
        PluginDescriptor b = plugin("b", "1.0.0", dep("a", "1.0.0"));
        assertThrows(CircularDependencyException.class, () -> resolver.resolve(List.of(a, b)));
    }

    @Test
    void testThreeNodeCycleDetected() {
        PluginDescriptor a = plugin("a", "1.0.0", dep("c", "1.0.0"));
        PluginDescriptor b = plugin("b", "1.0.0", dep("a", "1.0.0"));
        PluginDescriptor c = plugin("c", "1.0.0", dep("b", "1.0.0"));
        CircularDependencyException ex = assertThrows(CircularDependencyException.class,
                () -> resolver.resolve(List.of(a, b, c)));
        // 环路节点应包含在异常信息中
        assertFalse(ex.getCycle().isEmpty());
    }

    // ---- 分层排序 ----

    @Test
    void testSortByLevelsSingleWave() {
        List<List<PluginDescriptor>> levels = resolver.sortByLevels(
                List.of(plugin("a", "1.0.0"), plugin("b", "1.0.0")));
        assertEquals(1, levels.size());
        assertEquals(2, levels.get(0).size());
    }

    @Test
    void testSortByLevelsTwoWaves() {
        PluginDescriptor a = plugin("a", "1.0.0");
        PluginDescriptor b = plugin("b", "1.0.0");
        PluginDescriptor c = plugin("c", "1.0.0", dep("a", "1.0.0"), dep("b", "1.0.0"));

        resolver.resolve(List.of(a, b, c));
        List<List<PluginDescriptor>> levels = resolver.sortByLevels(List.of(a, b, c));

        assertEquals(2, levels.size());
        List<String> wave0 = levels.get(0).stream().map(PluginDescriptor::id).toList();
        List<String> wave1 = levels.get(1).stream().map(PluginDescriptor::id).toList();
        assertTrue(wave0.contains("a") && wave0.contains("b"));
        assertEquals(List.of("c"), wave1);
    }

    // ---- 重复 ID ----

    @Test
    void testDuplicatePluginIdThrows() {
        assertThrows(PluginLoadException.class, () -> resolver.resolve(List.of(
                plugin("a", "1.0.0"),
                plugin("a", "2.0.0")
        )));
    }
}
