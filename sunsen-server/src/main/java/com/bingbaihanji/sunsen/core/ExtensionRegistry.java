package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.ExtensionInfo;
import com.bingbaihanji.sunsen.api.ExtensionRegistrar;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;

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
    // sole 扩展点被拒绝的候选列表(所有访问均由 lock 保护)
    // key: 扩展点类型全名, value: 按 order 升序排列的被拒绝候选
    private final Map<String, List<ExtensionEntry<?>>> rejectedSoleCandidates = new HashMap<>();
    // 扩展生命周期钩子,可为空
    private ExtensionRegistrar registrar;

    /**
     * 将条目按 order 升序插入列表
     */
    private static void insertSortedByOrder(List<ExtensionEntry<?>> list, ExtensionEntry<?> entry) {
        int index = 0;
        for (; index < list.size(); index++) {
            if (list.get(index).order() > entry.order()) break;
        }
        list.add(index, entry);
    }

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
        String description = ext.description();

        // afterExtensionCreated is called here, outside any lock, for the winning entry.
        // If this entry later loses a sole-point conflict, afterExtensionDestroyed is
        // scheduled below (also outside the lock) to keep the lock window minimal.
        ExtensionEntry<?> entry;
        if (ext.singleton()) {
            Object instance = createInstance(getConstructor(implClass));
            entry = new ExtensionEntry<>(instance, extensionId, order, description, descriptor.id(), true, null, extensionPointType);
            if (registrar != null) {
                try {
                    registrar.afterExtensionCreated(instance, extensionPointType);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "ExtensionRegistrar.afterExtensionCreated failed for " + implClass.getName(), e);
                }
            }
        } else {
            entry = new ExtensionEntry<>(null, extensionId, order, description, descriptor.id(), false, getConstructor(implClass), extensionPointType);
        }

        ExtensionPoint ep = extensionPointType.getAnnotation(ExtensionPoint.class);
        boolean isSole = ep != null && ep.sole();

        // Deferred destroy callback: collected inside the lock, invoked outside to avoid
        // holding the write lock during external (potentially slow or re-entrant) calls.
        Object pendingDestroyInstance = null;
        Class<?> pendingDestroyType = null;
        boolean registered = true;

        lock.writeLock().lock();
        try {
            String key = extensionPointType.getName();
            List<ExtensionEntry<?>> list = entries.computeIfAbsent(key, k -> new ArrayList<>());

            // sole 扩展点：全局只能有一个插件实现，冲突时保留 order 最小（优先级最高）的
            if (isSole && !list.isEmpty()) {
                ExtensionEntry<?> existing = list.get(0);
                if (!existing.pluginId().equals(descriptor.id())) {
                    if (order < existing.order()) {
                        LOGGER.log(System.Logger.Level.WARNING,
                                () -> String.format("Sole extension point '%s' has multiple plugin implementations: " +
                                                "plugin '%s'(order=%d) and plugin '%s'(order=%d). Keeping '%s' with higher priority.",
                                        key, existing.pluginId(), existing.order(),
                                        descriptor.id(), order, descriptor.id()));
                        // Schedule destroy callback for displaced existing entry (invoked after lock release)
                        if (registrar != null && existing.singleton()) {
                            pendingDestroyInstance = existing.getInstance();
                            pendingDestroyType = extensionPointType;
                        }
                        // 将被逐出的旧条目存入候选列表（按 order 升序）
                        insertSortedByOrder(rejectedSoleCandidates.computeIfAbsent(key, k -> new ArrayList<>()), existing);
                        list.clear();
                    } else {
                        LOGGER.log(System.Logger.Level.WARNING,
                                () -> String.format("Sole extension point '%s' has multiple plugin implementations: " +
                                                "plugin '%s'(order=%d) and plugin '%s'(order=%d). Keeping '%s' with higher priority.",
                                        key, existing.pluginId(), existing.order(),
                                        descriptor.id(), order, existing.pluginId()));
                        // New entry loses: schedule destroy callback for it (invoked after lock release)
                        if (registrar != null && entry.singleton()) {
                            pendingDestroyInstance = entry.getInstance();
                            pendingDestroyType = extensionPointType;
                        }
                        // 将被拒绝的新条目存入候选列表（按 order 升序）
                        insertSortedByOrder(rejectedSoleCandidates.computeIfAbsent(key, k -> new ArrayList<>()), entry);
                        registered = false;
                    }
                }
            }

            if (registered) {
                // 按 order 升序插入
                int index = 0;
                for (; index < list.size(); index++) {
                    if (list.get(index).order() > order) {
                        break;
                    }
                }
                list.add(index, entry);
            }
        } finally {
            lock.writeLock().unlock();
        }

        // Invoke deferred destroy callback outside the write lock
        if (pendingDestroyInstance != null) {
            try {
                registrar.afterExtensionDestroyed(pendingDestroyInstance, pendingDestroyType);
            } catch (Exception ex) {
                final Object inst = pendingDestroyInstance;
                LOGGER.log(System.Logger.Level.ERROR,
                        () -> "ExtensionRegistrar.afterExtensionDestroyed callback error: " + inst.getClass().getName(), ex);
            }
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
            return Collections.unmodifiableList(result);
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
     * 获取指定扩展点的元数据列表(不持有扩展实例引用)
     */
    public <T> List<ExtensionInfo> getExtensionInfos(Class<T> extensionPointType) {
        lock.readLock().lock();
        try {
            List<ExtensionEntry<?>> list = entries.get(extensionPointType.getName());
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            List<ExtensionInfo> result = new ArrayList<>(list.size());
            for (ExtensionEntry<?> e : list) {
                result.add(new ExtensionInfo(
                        e.extensionId(), e.order(), e.description(),
                        e.pluginId(), e.singleton(), e.extensionPointType()
                ));
            }
            return Collections.unmodifiableList(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 注销指定插件的所有扩展.
     * <p>
     * 所有注册表变更在写锁内完成；{@link ExtensionRegistrar} 回调在锁释放后统一触发，
     * 避免外部代码持有写锁期间再次调用 {@code getExtensions}（读锁）造成长时间阻塞或死锁。
     */
    public void unregisterPlugin(String pluginId) {
        // Callbacks to invoke after the lock is released
        record PendingCallback(Object instance, Class<?> epType, boolean create) {
        }
        List<PendingCallback> pending = new ArrayList<>();

        lock.writeLock().lock();
        try {
            List<String> emptyKeys = new ArrayList<>();
            for (Map.Entry<String, List<ExtensionEntry<?>>> entry : entries.entrySet()) {
                List<ExtensionEntry<?>> list = entry.getValue();
                list.removeIf(e -> {
                    if (e.pluginId().equals(pluginId)) {
                        if (registrar != null && e.singleton()) {
                            pending.add(new PendingCallback(e.getInstance(), e.extensionPointType(), false));
                        }
                        return true;
                    }
                    return false;
                });
                if (list.isEmpty()) {
                    emptyKeys.add(entry.getKey());
                }
            }
            for (String key : emptyKeys) {
                entries.remove(key);
            }

            // 清理该插件在候选列表中的条目
            rejectedSoleCandidates.values().forEach(list -> list.removeIf(e -> e.pluginId().equals(pluginId)));
            rejectedSoleCandidates.entrySet().removeIf(e -> e.getValue().isEmpty());

            // sole 幸存者恢复：对于因删除而变空的扩展点，若有候选则自动接管
            for (String key : emptyKeys) {
                List<ExtensionEntry<?>> candidates = rejectedSoleCandidates.get(key);
                if (candidates != null && !candidates.isEmpty()) {
                    ExtensionEntry<?> best = candidates.remove(0);
                    if (candidates.isEmpty()) {
                        rejectedSoleCandidates.remove(key);
                    }
                    entries.computeIfAbsent(key, k -> new ArrayList<>()).add(best);
                    if (registrar != null && best.singleton()) {
                        pending.add(new PendingCallback(best.getInstance(), best.extensionPointType(), true));
                    }
                    LOGGER.log(System.Logger.Level.INFO,
                            () -> String.format("Sole extension point '%s': reinstated plugin '%s'(order=%d) after winner unloaded.",
                                    key, best.pluginId(), best.order()));
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        // Invoke callbacks outside the write lock
        for (PendingCallback cb : pending) {
            try {
                if (cb.create()) {
                    registrar.afterExtensionCreated(cb.instance(), cb.epType());
                } else {
                    registrar.afterExtensionDestroyed(cb.instance(), cb.epType());
                }
            } catch (Exception ex) {
                LOGGER.log(System.Logger.Level.ERROR,
                        () -> "ExtensionRegistrar callback error for " + cb.instance().getClass().getName(), ex);
            }
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
     * @param description        扩展描述文本
     * @param pluginId           所属插件 ID
     * @param singleton          是否为单例
     * @param constructor        无参构造器(非单例模式下使用)
     * @param extensionPointType 扩展点接口类型
     */
    record ExtensionEntry<T>(T cachedInstance,
                             String extensionId,
                             int order,
                             String description,
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
