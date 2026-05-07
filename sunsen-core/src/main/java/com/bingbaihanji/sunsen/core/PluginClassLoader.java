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
    private List<PluginClassLoader> dependencyLoaders;

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
     * 校验所有插件的 packagePrefixes 是否存在冲突
     */
    public static void validatePrefixes(Collection<PluginDescriptor> descriptors) {
        Set<String> reserved = new HashSet<>(PARENT_FIRST_PACKAGES);
        Map<String, String> prefixToPlugin = new HashMap<>();

        for (PluginDescriptor descriptor : descriptors) {
            for (String prefix : descriptor.packagePrefixes()) {
                // 与保留前缀冲突检测
                for (String reservedPrefix : reserved) {
                    if (prefix.startsWith(reservedPrefix) || reservedPrefix.startsWith(prefix)) {
                        throw new PluginLoadException(
                                "插件 " + descriptor.id() + " 的 packagePrefix '" + prefix
                                        + "' 与保留前缀 '" + reservedPrefix + "' 冲突"
                        );
                    }
                }
                // 与其他插件前缀冲突检测(包含关系也视为冲突)
                for (Map.Entry<String, String> entry : prefixToPlugin.entrySet()) {
                    String existingPrefix = entry.getKey();
                    if (prefix.startsWith(existingPrefix) || existingPrefix.startsWith(prefix)) {
                        throw new PluginLoadException(
                                "插件 " + descriptor.id() + " 与插件 " + entry.getValue()
                                        + " 的 packagePrefix 存在包含冲突: '" + prefix + "' vs '" + existingPrefix + "'"
                        );
                    }
                }
                prefixToPlugin.put(prefix, descriptor.id());
            }
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
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
            } catch (ClassNotFoundException ignored) {
                // 继续下一步
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
            if (className.startsWith(prefix)) {
                if (matched == null || prefix.length() > matched.length()) {
                    matched = prefix;
                }
            }
        }
        return matched;
    }
}
