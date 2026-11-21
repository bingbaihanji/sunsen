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
 * 从插件 JAR 中读取 {@code META-INF/plugin.json} 与可选的 {@code META-INF/plugin-metadata.json},
 * 并解析为 {@link PluginDescriptor}
 * <p>
 * {@code META-INF/plugin.json} 为必填描述文件;{@code META-INF/plugin-metadata.json} 为可选的结构化元数据资源,
 * 当 {@code plugin.json} 未提供 {@code metadata} 字段时作为元数据来源.
 *
 * @author 冰白寒祭
 * @date 2025
 */
public final class PluginDescriptorLoader {

    // 插件描述文件在 JAR 中的固定路径
    private static final String PLUGIN_JSON = "META-INF/plugin.json";
    // 结构化元数据资源在 JAR 中的约定路径
    private static final String PLUGIN_METADATA_JSON = "META-INF/plugin-metadata.json";
    // 日志记录器
    private static final System.Logger LOGGER = System.getLogger(PluginDescriptorLoader.class.getName());
    // JSON 解析器
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 合法 Java 包名正则
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*");
    // 插件 ID 白名单:字母数字.-_   ,防止路径遍历攻击
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");

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
                return parse(root, readMetadataResource(jarFile));
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
        return parse(root, null);
    }

    /**
     * 从 JSON 节点解析插件描述符,并合并已读取的资源元数据.
     *
     * @param root                 plugin.json 对应的 JSON 节点
     * @param resourceMetadataJson 已读取的 {@code META-INF/plugin-metadata.json} 文本;null 表示无
     * @return 解析后的插件描述符
     */
    static PluginDescriptor parse(JsonNode root, String resourceMetadataJson) {
        require(root, "id");
        require(root, "name");
        require(root, "version");
        require(root, "apiVersion");
        require(root, "mainClass");
        require(root, "packagePrefixes");

        String id = requireNonBlank(root.get("id").asText(), "id");
        if (!PLUGIN_ID_PATTERN.matcher(id).matches()) {
            throw new PluginLoadException("插件 ID 包含非法字符: '" + id + "',仅允许字母,数字,点,下划线,连字符");
        }
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

        Map<String, String> mappings = new LinkedHashMap<>();
        JsonNode mappingsNode = root.get("mappings");
        if (mappingsNode != null && !mappingsNode.isNull()) {
            if (!mappingsNode.isObject()) {
                throw new PluginLoadException("plugin.json mappings must be a JSON object");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = mappingsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().isBlank()) {
                    throw new PluginLoadException("plugin.json mappings key must not be blank");
                }
                if (!field.getValue().isTextual()) {
                    throw new PluginLoadException(
                            "plugin.json mappings value must be a string: " + field.getKey());
                }
                mappings.put(field.getKey(), field.getValue().asText());
            }
        }

        String metadataJson = "";
        JsonNode metadataNode = root.get("metadata");
        if (metadataNode != null && !metadataNode.isNull()) {
            if (!metadataNode.isObject()) {
                throw new PluginLoadException("plugin.json metadata must be a JSON object");
            }
            metadataJson = metadataNode.toString();
            if (resourceMetadataJson != null) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "plugin.json metadata overrides " + PLUGIN_METADATA_JSON
                                + " for plugin '" + id + "'");
            }
        } else if (resourceMetadataJson != null) {
            metadataJson = resourceMetadataJson;
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
                packagePrefixes, dependencies, permissions, vendorInfo,
                mappings, metadataJson
        );
    }

    /**
     * 读取并校验 {@code META-INF/plugin-metadata.json}.
     *
     * @param jarFile 已打开的插件 JAR
     * @return 规范化后的 JSON 文本;资源不存在或为空时返回 null
     */
    private static String readMetadataResource(JarFile jarFile) {
        var entry = jarFile.getJarEntry(PLUGIN_METADATA_JSON);
        if (entry == null) {
            return null;
        }
        try (InputStream is = jarFile.getInputStream(entry)) {
            JsonNode root = MAPPER.readTree(is);
            if (root == null || root.isMissingNode()) {
                return null;
            }
            if (!root.isObject()) {
                throw new PluginLoadException(
                        PLUGIN_METADATA_JSON + " in " + jarFile.getName() + " must be a JSON object");
            }
            return root.toString();
        } catch (IOException e) {
            throw new PluginLoadException(
                    "Cannot read " + PLUGIN_METADATA_JSON + " in " + jarFile.getName(), e);
        }
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
