package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * 插件专用类加载器,继承 {@link URLClassLoader}
 */
public class PluginClassLoader extends URLClassLoader {

    // 强制委托给父 ClassLoader 加载的包前缀集合
    private static final Set<String> PARENT_FIRST_PACKAGES = Set.of(
            "java.", "javax.", "sun.", "com.bingbaihanji.sunsen.api."
    );

    // 当前插件负责的包前缀列表
    private final List<String> packagePrefixes;
    // 依赖插件的 ClassLoader 列表
    private volatile List<PluginClassLoader> dependencyLoaders;

    /**
     * @param urls              插件 JAR 的 URL 数组
     * @param parent            父类加载器
     * @param packagePrefixes   插件包前缀列表
     * @param dependencyLoaders 依赖插件的类加载器列表
     */
    public PluginClassLoader(URL[] urls,
                             ClassLoader parent,
                             List<String> packagePrefixes,
                             List<PluginClassLoader> dependencyLoaders) {
        super(urls, parent);
        this.packagePrefixes = List.copyOf(packagePrefixes);
        this.dependencyLoaders = List.copyOf(dependencyLoaders);
    }

    /**
     * 判断类名是否属于给定的包前缀范围.
     * 匹配规则:类名与前缀完全相等,或类名以"前缀."开头.
     * 这样可以避免 com.foo 前缀错误匹配到 com.foobar.Bar 的情况.
     *
     * @param className 类全限定名
     * @param prefix    包前缀
     * @return true 如果类名属于该前缀范围
     */
    public static boolean matchesPackagePrefix(String className, String prefix) {
        if (className == null || prefix == null) {
            return false;
        }
        return className.equals(prefix) || className.startsWith(prefix + ".");
    }

    /**
     * 校验所有插件的 packagePrefixes 是否存在冲突.
     * 跨插件的前缀包含关系视为冲突;同一插件内部允许包含关系(如 com.foo 和 com.foo.api),
     * 但不允许完全重复.
     */
    public static void validatePrefixes(Collection<PluginDescriptor> descriptors) {
        Set<String> reserved = new HashSet<>(PARENT_FIRST_PACKAGES);
        Map<String, String> prefixToPlugin = new HashMap<>();

        for (PluginDescriptor descriptor : descriptors) {
            Set<String> seenInPlugin = new HashSet<>();
            for (String prefix : descriptor.packagePrefixes()) {
                // 同插件内重复检测
                if (!seenInPlugin.add(prefix)) {
                    throw new PluginLoadException(
                            "Plugin " + descriptor.id() + " has duplicate packagePrefix: '" + prefix + "'"
                    );
                }
                // 与保留前缀冲突检测
                for (String reservedPrefix : reserved) {
                    if (matchesPackagePrefix(prefix, reservedPrefix) || matchesPackagePrefix(reservedPrefix, prefix)) {
                        throw new PluginLoadException(
                                "Plugin " + descriptor.id() + " packagePrefix '" + prefix
                                        + "' conflicts with reserved prefix '" + reservedPrefix + "'"
                        );
                    }
                }
                // 与其他插件前缀冲突检测(跨插件才报错)
                for (Map.Entry<String, String> entry : prefixToPlugin.entrySet()) {
                    String existingPrefix = entry.getKey();
                    String existingPlugin = entry.getValue();
                    if (descriptor.id().equals(existingPlugin)) {
                        continue; // 同一插件内允许包含关系
                    }
                    if (matchesPackagePrefix(prefix, existingPrefix) || matchesPackagePrefix(existingPrefix, prefix)) {
                        throw new PluginLoadException(
                                "Plugin " + descriptor.id() + " and plugin " + existingPlugin
                                        + " have conflicting packagePrefix: '" + prefix + "' vs '" + existingPrefix + "'"
                        );
                    }
                }
                prefixToPlugin.put(prefix, descriptor.id());
            }
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. 已加载类
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }

            // 2. 公共 API 与 JDK 类:强制委托父 ClassLoader
            for (String prefix : PARENT_FIRST_PACKAGES) {
                if (name.startsWith(prefix)) {
                    return super.loadClass(name, resolve);
                }
            }

            // 3. 插件自有类:打破双亲委派,自行加载(使用最长前缀匹配)
            String matchedPrefix = findLongestMatchingPrefix(name);
            if (matchedPrefix != null) {
                try {
                    Class<?> c = findClass(name);
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                } catch (ClassNotFoundException e) {
                    // 明确属于本插件前缀但找不到 → 立即失败,
                    // 防止穿透到父 ClassLoader 破坏隔离语义
                    throw e;
                }
            }

            // 4. 依赖插件 ClassLoader 链查找
            for (PluginClassLoader loader : dependencyLoaders) {
                try {
                    return loader.loadClass(name, resolve);
                } catch (ClassNotFoundException ignored) {
                    // 继续下一个依赖
                }
            }

            // 5. 兜底委托父 ClassLoader
            return super.loadClass(name, resolve);
        }
    }

    /**
     * 动态更新依赖插件的 ClassLoader 列表
     * 用于热重载场景:当被依赖插件替换为新版本后,依赖方需要更新引用
     *
     * @param dependencyLoaders 新的依赖 ClassLoader 列表
     */
    public void updateDependencyLoaders(List<PluginClassLoader> dependencyLoaders) {
        this.dependencyLoaders = List.copyOf(dependencyLoaders);
    }

    /**
     * 查找与类名匹配的最长包前缀
     *
     * @param className 类全限定名
     * @return 最长匹配前缀,无匹配时返回 null
     */
    private String findLongestMatchingPrefix(String className) {
        String matched = null;
        for (String prefix : packagePrefixes) {
            if (matchesPackagePrefix(className, prefix)) {
                if (matched == null || prefix.length() > matched.length()) {
                    matched = prefix;
                }
            }
        }
        return matched;
    }
}
