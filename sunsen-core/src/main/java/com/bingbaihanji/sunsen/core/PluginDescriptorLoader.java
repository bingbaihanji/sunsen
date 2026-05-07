package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.DependencyDescriptor;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/**
 * 从插件 JAR 中读取 {@code META-INF/plugin.json} 并解析为 {@link PluginDescriptor}
 */
public final class PluginDescriptorLoader {

    // 插件描述文件在 JAR 中的固定路径
    private static final String PLUGIN_JSON = "META-INF/plugin.json";
    // JSON 解析器
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 禁止实例化
     */
    private PluginDescriptorLoader() {
    }

    /**
     * 从 JAR 文件加载插件描述符
     *
     * @param jarPath 插件 JAR 路径
     * @return 解析后的插件描述符
     */
    public static PluginDescriptor load(Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var entry = jarFile.getJarEntry(PLUGIN_JSON);
            if (entry == null) {
                throw new PluginLoadException("JAR 中缺失 " + PLUGIN_JSON + ": " + jarPath);
            }
            try (InputStream is = jarFile.getInputStream(entry)) {
                return parse(MAPPER.readTree(is));
            }
        } catch (IOException e) {
            throw new PluginLoadException("无法读取插件描述文件: " + jarPath, e);
        }
    }

    /**
     * 从 JSON 节点解析插件描述符
     *
     * @param root plugin.json 对应的 JSON 节点
     * @return 解析后的插件描述符
     */
    static PluginDescriptor parse(JsonNode root) {
        require(root, "id");
        require(root, "name");
        require(root, "version");
        require(root, "apiVersion");
        require(root, "mainClass");
        require(root, "packagePrefixes");

        String id = root.get("id").asText();
        String name = root.get("name").asText();
        String description = getText(root, "description", "");
        String version = root.get("version").asText();
        String apiVersion = root.get("apiVersion").asText();
        String mainClass = root.get("mainClass").asText();

        JsonNode prefixesNode = root.get("packagePrefixes");
        if (!prefixesNode.isArray()) {
            throw new PluginLoadException("plugin.json 中 packagePrefixes 必须是 JSON 数组");
        }
        List<String> packagePrefixes = new ArrayList<>();
        for (JsonNode node : prefixesNode) {
            packagePrefixes.add(node.asText());
        }

        List<DependencyDescriptor> dependencies = new ArrayList<>();
        if (root.has("dependencies")) {
            JsonNode depsNode = root.get("dependencies");
            if (!depsNode.isArray()) {
                throw new PluginLoadException("plugin.json 中 dependencies 必须是 JSON 数组");
            }
            for (JsonNode dep : depsNode) {
                String depId = require(dep, "id").asText();
                String depVersion = require(dep, "version").asText();
                boolean optional = dep.path("optional").asBoolean(false);
                dependencies.add(new DependencyDescriptor(depId, depVersion, optional));
            }
        }

        Set<String> permissions = new HashSet<>();
        if (root.has("permissions")) {
            JsonNode permsNode = root.get("permissions");
            if (!permsNode.isArray()) {
                throw new PluginLoadException("plugin.json 中 permissions 必须是 JSON 数组");
            }
            for (JsonNode perm : permsNode) {
                permissions.add(perm.asText());
            }
        }

        Map<String, String> vendorInfo = new HashMap<>();
        if (root.has("vendor")) {
            JsonNode vendor = root.get("vendor");
            if (vendor.has("name")) vendorInfo.put("name", vendor.get("name").asText());
            if (vendor.has("url")) vendorInfo.put("url", vendor.get("url").asText());
        }

        return new PluginDescriptor(
                id, name, description, version, apiVersion, mainClass,
                packagePrefixes, dependencies, permissions, vendorInfo
        );
    }

    /**
     * 校验必填字段是否存在
     */
    private static JsonNode require(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            throw new PluginLoadException("plugin.json 缺失必填字段: " + field);
        }
        return parent.get(field);
    }

    /**
     * 读取文本字段,不存在时返回默认值
     */
    private static String getText(JsonNode parent, String field, String defaultValue) {
        return parent.has(field) && !parent.get(field).isNull() ? parent.get(field).asText() : defaultValue;
    }
}
