package com.bingbaihanji.sunsen.api.support;

import com.bingbaihanji.sunsen.api.PluginContext;

import java.util.Arrays;
import java.util.List;

/**
 * 类型安全的插件配置读取工具,对 {@link PluginContext#getProperty} 的封装.
 * <p>
 * 类型解析失败时静默返回默认值,不抛出异常——插件配置属于开发者可控输入,
 * 解析失败通常是配置错误而非程序 bug,用默认值降级比运行时崩溃更合理.
 *
 * <pre>{@code
 * public class MyPlugin extends AbstractPlugin {
 *     @Override
 *     protected void onInitialized() {
 *         PluginProperties props = PluginProperties.of(context());
 *
 *         String host    = props.require("db.host");              // 必填,缺失则抛出
 *         int    port    = props.getInt("db.port", 5432);
 *         boolean debug  = props.getBoolean("debug", false);
 *         List<String> allowedIps = props.getList("allowed.ips"); // "127.0.0.1,10.0.0.1"
 *         LogLevel level = props.getEnum("log.level", LogLevel.class, LogLevel.INFO);
 *     }
 * }
 * }</pre>
 */
public final class PluginProperties {

    private final PluginContext context;

    private PluginProperties(PluginContext context) {
        this.context = context;
    }

    /**
     * 从插件上下文创建配置读取实例.
     */
    public static PluginProperties of(PluginContext context) {
        return new PluginProperties(context);
    }

    // ---- 字符串 ----

    /**
     * 读取字符串配置,不存在返回 {@code null}.
     */
    public String getString(String key) {
        return context.getProperty(key);
    }

    /**
     * 读取字符串配置,不存在或空白时返回 {@code defaultValue}.
     */
    public String getString(String key, String defaultValue) {
        return context.getProperty(key, defaultValue);
    }

    /**
     * 断言配置项存在且非空白,否则抛出 {@link IllegalStateException}.
     * 适用于必填配置项,建议在 {@code onInitialized()} 中调用以提前发现配置错误.
     */
    public String require(String key) {
        String value = context.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Plugin '" + context.getDescriptor().id()
                            + "' requires configuration key: " + key);
        }
        return value.trim();
    }

    // ---- 数值 ----

    /**
     * 读取 int 配置;缺失或解析失败返回 {@code defaultValue}.
     */
    public int getInt(String key, int defaultValue) {
        String value = context.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * 读取 long 配置;缺失或解析失败返回 {@code defaultValue}.
     */
    public long getLong(String key, long defaultValue) {
        String value = context.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * 读取 double 配置;缺失或解析失败返回 {@code defaultValue}.
     */
    public double getDouble(String key, double defaultValue) {
        String value = context.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    // ---- 布尔 ----

    /**
     * 读取 boolean 配置,仅识别 {@code true} / {@code false}(大小写不敏感),
     * 其他值(包括 {@code 1},{@code yes})返回 {@code defaultValue}.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = context.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        return defaultValue;
    }

    // ---- 枚举 ----

    /**
     * 读取枚举配置,匹配枚举常量名(不区分大小写);缺失或无匹配项返回 {@code defaultValue}.
     *
     * <pre>{@code
     * LogLevel level = props.getEnum("log.level", LogLevel.class, LogLevel.INFO);
     * }</pre>
     *
     * @param key          配置键
     * @param enumType     枚举类型
     * @param defaultValue 解析失败时的默认值
     */
    public <E extends Enum<E>> E getEnum(String key, Class<E> enumType, E defaultValue) {
        String value = context.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
    }

    // ---- 列表 ----

    /**
     * 读取逗号分隔的字符串列表;每项自动去除首尾空白,过滤空项.
     * 配置不存在时返回空列表.
     *
     * <pre>{@code
     * // 配置: allowed.hosts=127.0.0.1, 10.0.0.1, ::1
     * List<String> hosts = props.getList("allowed.hosts");
     * // → ["127.0.0.1", "10.0.0.1", "::1"]
     * }</pre>
     */
    public List<String> getList(String key) {
        return getList(key, ",");
    }

    /**
     * 读取以 {@code delimiter} 分隔的字符串列表;每项自动去除首尾空白,过滤空项.
     * 配置不存在时返回空列表.
     *
     * @param key       配置键
     * @param delimiter 分隔符(作为正则传入 {@link String#split},特殊字符需转义)
     */
    public List<String> getList(String key, String delimiter) {
        String value = context.getProperty(key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(delimiter))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // ---- 实用 ----

    /**
     * 判断配置项是否存在(值非 null).
     */
    public boolean contains(String key) {
        return context.getProperty(key) != null;
    }
}
