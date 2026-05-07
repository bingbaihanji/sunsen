package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.ExtensionRegistrar;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.annotation.Extension;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 扩展注册表,管理所有插件的扩展实例
 * <p>
 * 内部使用读写锁保证并发安全,支持单例与非单例两种扩展模式,
 * 并通过 {@link ExtensionRegistrar} 钩子将扩展生命周期同步到宿主容器
 */
public class ExtensionRegistry {

    private static final System.Logger LOGGER = System.getLogger(ExtensionRegistry.class.getName());

    // 读写锁,保护扩展注册表的并发访问
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    // 扩展点类型全名 -> 扩展条目列表(所有访问均由 lock 保护,无需 ConcurrentHashMap)
    private final Map<String, List<ExtensionEntry<?>>> entries = new HashMap<>();
    // 扩展生命周期钩子,可为空
    private ExtensionRegistrar registrar;

    /**
     * 通过无参构造器创建扩展实例
     */
    private static Object createInstance(Constructor<?> ctor) {
        try {
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("无法实例化扩展类: " + ctor.getDeclaringClass().getName(), e);
        }
    }

    /**
     * 缓存并返回类的无参构造器
     */
    private static Constructor<?> getConstructor(Class<?> clazz) {
        try {
            return clazz.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("扩展类缺少无参构造器: " + clazz.getName(), e);
        }
    }

    /**
     * 设置扩展实例生命周期钩子
     *
     * @param registrar 扩展注册器实现
     */
    public void setExtensionRegistrar(ExtensionRegistrar registrar) {
        this.registrar = registrar;
    }

    /**
     * 注册单个扩展
     *
     * @param descriptor         插件描述符
     * @param implClass          扩展实现类(必须标注 {@link Extension})
     * @param extensionPointType 扩展点接口类型
     */
    public void register(PluginDescriptor descriptor, Class<?> implClass, Class<?> extensionPointType) {
        Extension ext = implClass.getAnnotation(Extension.class);
        if (ext == null) {
            throw new IllegalArgumentException("Class " + implClass.getName() + " is not annotated with @Extension");
        }

        String extensionId = ext.id().isBlank() ? implClass.getName() : ext.id();
        int order = ext.order();

        ExtensionEntry<?> entry;
        if (ext.singleton()) {
            Object instance = createInstance(getConstructor(implClass));
            entry = new ExtensionEntry<>(instance, extensionId, order, descriptor.id(), true, null, extensionPointType);
            if (registrar != null) {
                try {
                    registrar.afterExtensionCreated(instance, extensionPointType);
                } catch (Exception e) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            () -> "ExtensionRegistrar.afterExtensionCreated 回调异常: " + implClass.getName(), e);
                }
            }
        } else {
            entry = new ExtensionEntry<>(null, extensionId, order, descriptor.id(), false, getConstructor(implClass), extensionPointType);
        }

        lock.writeLock().lock();
        try {
            String key = extensionPointType.getName();
            List<ExtensionEntry<?>> list = entries.computeIfAbsent(key, k -> new ArrayList<>());
            // 按 order 升序插入
            int index = 0;
            for (; index < list.size(); index++) {
                if (list.get(index).order() > order) {
                    break;
                }
            }
            list.add(index, entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取指定扩展点的所有实现,按 order 升序排列
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getExtensions(Class<T> extensionPointType) {
        lock.readLock().lock();
        try {
            List<ExtensionEntry<?>> list = entries.get(extensionPointType.getName());
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            List<T> result = new ArrayList<>(list.size());
            for (ExtensionEntry<?> e : list) {
                result.add((T) e.getInstance());
            }
            return List.copyOf(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按扩展 id 精确获取单个扩展实例
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getExtension(Class<T> extensionPointType, String extensionId) {
        lock.readLock().lock();
        try {
            List<ExtensionEntry<?>> list = entries.get(extensionPointType.getName());
            if (list == null) return Optional.empty();
            for (ExtensionEntry<?> entry : list) {
                if (entry.extensionId().equals(extensionId)) {
                    return Optional.of((T) entry.getInstance());
                }
            }
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 注销指定插件的所有扩展
     */
    public void unregisterPlugin(String pluginId) {
        lock.writeLock().lock();
        try {
            for (List<ExtensionEntry<?>> list : entries.values()) {
                list.removeIf(entry -> {
                    if (entry.pluginId().equals(pluginId)) {
                        if (registrar != null) {
                            try {
                                registrar.afterExtensionDestroyed(entry.getInstance(), entry.extensionPointType());
                            } catch (Exception e) {
                                LOGGER.log(System.Logger.Level.ERROR,
                                        () -> "ExtensionRegistrar.afterExtensionDestroyed 回调异常: " + entry.extensionId(), e);
                            }
                        }
                        return true;
                    }
                    return false;
                });
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 在写锁保护下执行批量操作
     */
    public void withWriteLock(Runnable action) {
        lock.writeLock().lock();
        try {
            action.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 扩展条目内部记录
     *
     * @param cachedInstance     单例模式下的缓存实例
     * @param extensionId        扩展唯一标识
     * @param order              排序权重
     * @param pluginId           所属插件 ID
     * @param singleton          是否为单例
     * @param constructor        无参构造器(非单例模式下使用)
     * @param extensionPointType 扩展点接口类型
     */
    record ExtensionEntry<T>(T cachedInstance,
                             String extensionId,
                             int order,
                             String pluginId,
                             boolean singleton,
                             Constructor<?> constructor,
                             Class<?> extensionPointType) {

        @SuppressWarnings("unchecked")
        T getInstance() {
            if (singleton) {
                return cachedInstance;
            }
            return (T) createInstance(constructor);
        }
    }
}
