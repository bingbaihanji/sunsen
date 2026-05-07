package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 扫描插件 JAR 中的 {@link Extension} 标注类并注册到 {@link ExtensionRegistry}
 */
public class ExtensionScanner {

    // 扩展注册表,扫描到的扩展将注册至此
    private final ExtensionRegistry registry;

    /**
     * @param registry 扩展注册表实例
     */
    public ExtensionScanner(ExtensionRegistry registry) {
        this.registry = registry;
    }

    /**
     * 推断扩展实现类对应的扩展点接口类型
     */
    static Class<?> inferExtensionPointType(Class<?> implClass) {
        for (Class<?> iface : getAllInterfaces(implClass)) {
            if (iface.isAnnotationPresent(ExtensionPoint.class)) {
                return iface;
            }
        }
        return null;
    }

    private static Set<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> result = new LinkedHashSet<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        stack.push(clazz);
        while (!stack.isEmpty()) {
            Class<?> current = stack.pop();
            if (current == Object.class) continue;
            for (Class<?> iface : current.getInterfaces()) {
                if (result.add(iface)) {
                    stack.push(iface);
                }
            }
            Class<?> superClass = current.getSuperclass();
            if (superClass != null) {
                stack.push(superClass);
            }
        }
        return result;
    }

    /**
     * 扫描指定插件 JAR 中的所有扩展实现
     *
     * @param descriptor  插件描述符
     * @param classLoader 插件类加载器
     */
    public void scan(PluginDescriptor descriptor, PluginClassLoader classLoader) {
        List<Class<?>> extensionClasses = new ArrayList<>();
        for (URL url : classLoader.getURLs()) {
            if (!"file".equals(url.getProtocol())) continue;
            try {
                Path jarPath = Path.of(url.toURI());
                if (!Files.exists(jarPath)) continue;
                try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!name.endsWith(".class")) continue;
                        String className = name.replace('/', '.').substring(0, name.length() - 6);
                        if (!belongsToPlugin(className, descriptor.packagePrefixes())) continue;
                        try {
                            // 使用 initialize=false 避免触发静态初始化器
                            Class<?> clazz = Class.forName(className, false, classLoader);
                            if (clazz.isAnnotationPresent(Extension.class)) {
                                extensionClasses.add(clazz);
                            }
                        } catch (ClassNotFoundException | NoClassDefFoundError e) {
                            // 跳过无法加载的类
                        }
                    }
                }
            } catch (Exception e) {
                throw new PluginLoadException("扫描插件扩展失败: " + descriptor.id(), e);
            }
        }

        // 推断并缓存扩展点类型,按扩展点类型分组用于 allowMultiple 校验
        Map<Class<?>, Class<?>> implToEpType = new LinkedHashMap<>(extensionClasses.size() * 2);
        Map<Class<?>, List<Class<?>>> byExtensionPoint = new LinkedHashMap<>();
        for (Class<?> implClass : extensionClasses) {
            Class<?> extensionPointType = inferExtensionPointType(implClass);
            if (extensionPointType != null) {
                implToEpType.put(implClass, extensionPointType);
                byExtensionPoint.computeIfAbsent(extensionPointType, k -> new ArrayList<>())
                        .add(implClass);
            }
        }

        validateSingleExtensions(byExtensionPoint);

        for (Map.Entry<Class<?>, Class<?>> entry : implToEpType.entrySet()) {
            registry.register(descriptor, entry.getKey(), entry.getValue());
        }
    }

    /**
     * 判断类名是否属于当前插件的包前缀范围
     */
    private boolean belongsToPlugin(String className, List<String> packagePrefixes) {
        for (String prefix : packagePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验 allowMultiple = false 的扩展点在同一插件内不能有多个实现
     *
     * @param extensionMap key = 扩展点接口类型,value = 该插件提供的实现类列表
     * @throws PluginLoadException 同一扩展点有多个实现且 allowMultiple=false
     */
    void validateSingleExtensions(Map<Class<?>, List<Class<?>>> extensionMap) {
        for (Map.Entry<Class<?>, List<Class<?>>> entry : extensionMap.entrySet()) {
            List<Class<?>> impls = entry.getValue();
            if (impls.size() <= 1) continue;
            Class<?> epType = entry.getKey();
            ExtensionPoint ep = epType.getAnnotation(ExtensionPoint.class);
            if (ep != null && !ep.allowMultiple()) {
                throw new PluginLoadException(
                        "扩展点 " + epType.getName() + " (allowMultiple=false) 在同一插件中只能有一个实现,"
                                + "但实际发现 " + impls.size() + " 个"
                );
            }
        }
    }
}
