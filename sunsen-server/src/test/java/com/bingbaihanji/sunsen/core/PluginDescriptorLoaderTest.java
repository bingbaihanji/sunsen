package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseCompleteJson() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "id": "com.example.plugin",
                  "name": "Example",
                  "description": "An example plugin",
                  "version": "1.2.3",
                  "apiVersion": "1.0",
                  "mainClass": "com.example.Main",
                  "packagePrefixes": ["com.example"],
                  "dependencies": [
                    {"id": "com.example.dep", "version": "^1.0.0"}
                  ],
                  "permissions": ["file:read"],
                  "vendor": {"name": "ACME", "url": "https://example.com"}
                }
                """);

        var descriptor = PluginDescriptorLoader.parse(root);

        assertEquals("com.example.plugin", descriptor.id());
        assertEquals("Example", descriptor.name());
        assertEquals("An example plugin", descriptor.description());
        assertEquals("1.2.3", descriptor.version());
        assertEquals("1.0", descriptor.apiVersion());
        assertEquals("com.example.Main", descriptor.mainClass());
        assertEquals(List.of("com.example"), descriptor.packagePrefixes());
        assertEquals(1, descriptor.dependencies().size());
        assertEquals("com.example.dep", descriptor.dependencies().get(0).id());
        assertEquals("^1.0.0", descriptor.dependencies().get(0).versionConstraint());
        assertFalse(descriptor.dependencies().get(0).optional());
        assertTrue(descriptor.permissions().contains("file:read"));
        assertEquals("ACME", descriptor.vendorInfo().get("name"));
    }

    @Test
    void parseMissingIdThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingNameThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingVersionThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingApiVersionThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingMainClassThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingPackagePrefixesThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M"}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseEmptyPackagePrefixesThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": []}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseTrailingDotPrefixNormalized() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["com.foo."]}
                """);
        var descriptor = PluginDescriptorLoader.parse(root);
        assertEquals("com.foo", descriptor.packagePrefixes().get(0));
    }

    @Test
    void parseDuplicateDependencyIdThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"],
                 "dependencies": [
                   {"id": "dep", "version": "1.0"},
                   {"id": "dep", "version": "2.0"}
                 ]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseSelfDependencyThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"],
                 "dependencies": [{"id": "x", "version": "1.0"}]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseOptionalDependency() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"],
                 "dependencies": [{"id": "dep", "version": "1.0", "optional": true}]}
                """);
        var descriptor = PluginDescriptorLoader.parse(root);
        assertTrue(descriptor.dependencies().get(0).optional());
    }
}
