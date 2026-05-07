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
import java.util.regex.Pattern;

/**
 * 从插件 JAR 中读取 {@code META-INF/plugin.json} 并解析为 {@link PluginDescriptor}
 */
public final class PluginDescriptorLoader {

    // 插件描述文件在 JAR 中的固定路径
    private static final String PLUGIN_JSON = "META-INF/plugin.json";
    // JSON 解析器
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 合法 Java 包名正则
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*");

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
                throw new PluginLoadException("JAR missing " + PLUGIN_JSON + ": " + jarPath);
            }
            try (InputStream is = jarFile.getInputStream(entry)) {
                JsonNode root = MAPPER.readTree(is);
                if (root == null || !root.isObject()) {
                    throw new PluginLoadException("plugin.json must be a JSON object: " + jarPath);
                }
                return parse(root);
            }
        } catch (IOException e) {
            throw new PluginLoadException("Cannot read plugin descriptor: " + jarPath, e);
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

        String id = requireNonBlank(root.get("id").asText(), "id");
        String name = requireNonBlank(root.get("name").asText(), "name");
        String description = getText(root, "description", "");
        String version = requireNonBlank(root.get("version").asText(), "version");
        String apiVersion = requireNonBlank(root.get("apiVersion").asText(), "apiVersion");
        String mainClass = requireNonBlank(root.get("mainClass").asText(), "mainClass");

        JsonNode prefixesNode = root.get("packagePrefixes");
        if (!prefixesNode.isArray()) {
            throw new PluginLoadException("plugin.json packagePrefixes must be a JSON array");
        }
        if (prefixesNode.isEmpty()) {
            throw new PluginLoadException("plugin.json packagePrefixes must contain at least one entry");
        }
        List<String> packagePrefixes = new ArrayList<>();
        for (JsonNode node : prefixesNode) {
            String prefix = requireNonBlank(node.asText(), "packagePrefixes item");
            prefix = normalizePrefix(prefix);
            if (!PACKAGE_PATTERN.matcher(prefix).matches()) {
                throw new PluginLoadException("Invalid package prefix: '" + prefix + "'");
            }
            packagePrefixes.add(prefix);
        }

        List<DependencyDescriptor> dependencies = new ArrayList<>();
        if (root.has("dependencies")) {
            JsonNode depsNode = root.get("dependencies");
            if (!depsNode.isArray()) {
                throw new PluginLoadException("plugin.json dependencies must be a JSON array");
            }
            Set<String> depIds = new HashSet<>();
            for (JsonNode dep : depsNode) {
                String depId = requireNonBlank(require(dep, "id").asText(), "dependency.id");
                String depVersion = requireNonBlank(require(dep, "version").asText(), "dependency.version");
                boolean optional = dep.path("optional").asBoolean(false);
                if (depId.equals(id)) {
                    throw new PluginLoadException("Plugin '" + id + "' has self-dependency");
                }
                if (!depIds.add(depId)) {
                    throw new PluginLoadException("Duplicate dependency ID '" + depId + "' in plugin '" + id + "'");
                }
                dependencies.add(new DependencyDescriptor(depId, depVersion, optional));
            }
        }

        Set<String> permissions = new HashSet<>();
        if (root.has("permissions")) {
            JsonNode permsNode = root.get("permissions");
            if (!permsNode.isArray()) {
                throw new PluginLoadException("plugin.json permissions must be a JSON array");
            }
            for (JsonNode perm : permsNode) {
                permissions.add(perm.asText());
            }
        }

        Map<String, String> vendorInfo = new HashMap<>();
        if (root.has("vendor")) {
            JsonNode vendor = root.get("vendor");
            if (vendor.isObject()) {
                Iterator<String> fieldNames = vendor.fieldNames();
                while (fieldNames.hasNext()) {
                    String field = fieldNames.next();
                    vendorInfo.put(field, vendor.get(field).asText());
                }
            }
        }

        return new PluginDescriptor(
                id, name, description, version, apiVersion, mainClass,
                packagePrefixes, dependencies, permissions, vendorInfo
        );
    }

    /**
     * 去掉包前缀末尾的点号,避免误匹配
     */
    private static String normalizePrefix(String prefix) {
        return prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    /**
     * 校验字符串非空且非空白
     */
    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PluginLoadException("plugin.json field '" + fieldName + "' must not be blank");
        }
        return value;
    }

    /**
     * 校验必填字段是否存在
     */
    private static JsonNode require(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            throw new PluginLoadException("plugin.json missing required field: " + field);
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
