package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PluginClassLoader} 单元测试
 * @author 冰白寒祭
 * @date 2025
 */
class PluginClassLoaderTest {

    // ---- matchesPackagePrefix ----

    @Test
    void matchesPackagePrefixExactMatch() {
        assertTrue(PluginClassLoader.matchesPackagePrefix("com.foo.Bar", "com.foo"));
    }

    @Test
    void matchesPackagePrefixWithDot() {
        assertTrue(PluginClassLoader.matchesPackagePrefix("com.foo.bar.Baz", "com.foo"));
    }

    @Test
    void matchesPackagePrefixNoMatch() {
        assertFalse(PluginClassLoader.matchesPackagePrefix("com.foobar.Baz", "com.foo"));
    }

    @Test
    void matchesPackagePrefixNullInputs() {
        assertFalse(PluginClassLoader.matchesPackagePrefix(null, "com.foo"));
        assertFalse(PluginClassLoader.matchesPackagePrefix("com.foo.Bar", null));
    }

    // ---- validatePrefixes ----

    @Test
    void validatePrefixesCrossPluginConflict() {
        PluginDescriptor pluginA = descriptor("plugin-a", List.of("com.foo"));
        PluginDescriptor pluginB = descriptor("plugin-b", List.of("com.foo.api"));
        assertThrows(PluginLoadException.class,
                () -> PluginClassLoader.validatePrefixes(List.of(pluginA, pluginB)));
    }

    @Test
    void validatePrefixesSamePluginAllowsContainment() {
        PluginDescriptor pluginA = descriptor("plugin-a", List.of("com.foo", "com.foo.api"));
        assertDoesNotThrow(() -> PluginClassLoader.validatePrefixes(List.of(pluginA)));
    }

    // ---- helper ----

    private static PluginDescriptor descriptor(String id, List<String> prefixes) {
        return new PluginDescriptor(id, id, "", "1.0", "1.0", "com.test.Main",
                prefixes, List.of(), java.util.Set.of(), java.util.Map.of());
    }
}
