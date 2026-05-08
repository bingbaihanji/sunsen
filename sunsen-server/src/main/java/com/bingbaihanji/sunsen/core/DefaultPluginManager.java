package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.*;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;
import com.bingbaihanji.sunsen.api.event.builtin.*;
import com.bingbaihanji.sunsen.core.lifecycle.LifecycleStateMachine;
import com.bingbaihanji.sunsen.core.version.SemVer;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link PluginManager} 的默认实现
 * <p>
 * 负责插件的批量/单例加载,启动,停止,卸载与热重载,整合扩展注册表,事件总线,
 * 依赖解析器与生命周期状态机,提供完整的插件生命周期管理能力
 */
public class DefaultPluginManager implements PluginManager {

    // 日志记录器
    private static final System.Logger LOGGER = System.getLogger(DefaultPluginManager.class.getName());
    // 扩展注册表,管理所有插件扩展实例
    private final ExtensionRegistry extensionRegistry;
    // 事件总线,负责事件的分发与订阅
    private final PluginEventBus eventBus;
    // 依赖解析器,负责依赖校验与拓扑排序
    private final DependencyResolver dependencyResolver;
    // 生命周期状态机,管理插件状态转换
    private final LifecycleStateMachine stateMachine;
    // 扩展扫描器,复用单一实例
    private final ExtensionScanner extensionScanner;
    // 已加载插件的映射表,key 为插件 ID
    private final Map<String, PluginEntry> plugins = new ConcurrentHashMap<>();
    // 插件加载顺序(拓扑排序结果),用于按序启停
    private final List<String> topology = new ArrayList<>();
    // 管理器级写锁,保护所有修改插件集合的操作
    private final ReentrantLock managementLock = new ReentrantLock();
    // 扩展实例生命周期钩子,可为空
    private ExtensionRegistrar extensionRegistrar;
    // 插件目录
    private Path pluginsDir = Path.of("plugins");
    // 父 ClassLoader
    private ClassLoader parentClassLoader = DefaultPluginManager.class.getClassLoader();

    /**
     * 使用默认同步事件总线构造管理器
     */
    public DefaultPluginManager() {
        this(new PluginEventBus());
    }

    /**
     * 使用指定事件总线构造管理器
     *
     * @param eventBus 自定义事件总线实例
     */
    public DefaultPluginManager(PluginEventBus eventBus) {
        this.eventBus = eventBus;
        this.extensionRegistry = new ExtensionRegistry();
        this.dependencyResolver = new DependencyResolver();
        this.stateMachine = new LifecycleStateMachine(eventBus);
        this.extensionScanner = new ExtensionScanner(extensionRegistry);
    }

    /**
     * 设置插件根目录
     *
     * @param pluginsDir 插件目录路径
     */
    public void setPluginsDir(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    /**
     * 设置父 ClassLoader
     *
     * @param parentClassLoader 父类加载器
     */
    public void setParentClassLoader(ClassLoader parentClassLoader) {
        this.parentClassLoader = parentClassLoader;
    }


    // 批量操作

    @Override
    public void loadPlugins() {
        if (!Files.exists(pluginsDir)) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "Plugin directory not found: " + pluginsDir);
            return;
        }

        List<Path> jarPaths = new ArrayList<>();
        try (var stream = Files.list(pluginsDir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(jarPaths::add);
        } catch (IOException e) {
            throw new PluginLoadException("Cannot scan plugin directory: " + pluginsDir, e);
        }

        List<PluginDescriptor> descriptors = new ArrayList<>();
        Map<String, Path> descriptorToJar = new HashMap<>();
        for (Path jarPath : jarPaths) {
            try {
                PluginDescriptor descriptor = PluginDescriptorLoader.load(jarPath);
                if (descriptorToJar.containsKey(descriptor.id())) {
                    throw new PluginLoadException("Duplicate plugin ID: '" + descriptor.id() + "' found in " + jarPath);
                }
                if (plugins.containsKey(descriptor.id())) {
                    throw new PluginLoadException("Plugin already loaded: '" + descriptor.id() + "' found in " + jarPath);
                }
                descriptors.add(descriptor);
                descriptorToJar.put(descriptor.id(), jarPath);
                stateMachine.init(descriptor.id(), PluginState.CREATED);
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR, () -> "Parse plugin failed: " + jarPath, e);
            }
        }

        if (descriptors.isEmpty()) {
            return;
        }

        PluginClassLoader.validatePrefixes(descriptors);
        dependencyResolver.resolve(descriptors);
        List<PluginDescriptor> sorted = dependencyResolver.sort(descriptors);

        managementLock.lock();
        try {
            for (PluginDescriptor descriptor : sorted) {
                try {
                    loadSinglePlugin(descriptor, descriptorToJar.get(descriptor.id()));
                    topology.add(descriptor.id());
                } catch (Exception e) {
                    LOGGER.log(System.Logger.Level.ERROR, () -> "Load plugin failed: " + descriptor.id(), e);
                    stateMachine.forceSet(descriptor.id(), PluginState.FAILED);
                    eventBus.publish(new PluginFailedEvent(descriptor, PluginState.CREATED, e));
                }
            }
        } finally {
            managementLock.unlock();
        }
    }

    @Override
    public void startPlugins() {
        List<String> snapshot = List.copyOf(topology);
        for (String pluginId : snapshot) {
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.LOADED) {
                startSinglePlugin(pluginId);
            }
        }
    }

    @Override
    public void stopPlugins() {
        List<String> snapshot = new ArrayList<>(topology);
        Collections.reverse(snapshot);
        for (String pluginId : snapshot) {
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.ACTIVE) {
                stopSinglePlugin(pluginId);
            }
        }
    }

    @Override
    public void unloadPlugins() {
        managementLock.lock();
        try {
            List<String> reverse = new ArrayList<>(topology);
            Collections.reverse(reverse);
            for (String pluginId : reverse) {
                PluginState state = stateMachine.getState(pluginId);
                if (state == null || state == PluginState.UNLOADED) {
                    continue;
                }
                if (state == PluginState.ACTIVE || state == PluginState.STARTING) {
                    stopSinglePlugin(pluginId);
                }
                unloadSinglePlugin(pluginId);
            }
        } finally {
            managementLock.unlock();
        }
    }


    // 单插件操作


    @Override
    public PluginDescriptor loadPlugin(Path jarPath) {
        PluginDescriptor descriptor = PluginDescriptorLoader.load(jarPath);
        if (plugins.containsKey(descriptor.id())) {
            throw new PluginLoadException("Plugin already exists: " + descriptor.id());
        }
        // Combine new plugin with already loaded ones for prefix and dependency validation
        List<PluginDescriptor> allDescriptors = new ArrayList<>(getPlugins());
        allDescriptors.add(descriptor);
        PluginClassLoader.validatePrefixes(allDescriptors);
        dependencyResolver.resolve(allDescriptors);

        stateMachine.init(descriptor.id(), PluginState.CREATED);

        managementLock.lock();
        try {
            loadSinglePlugin(descriptor, jarPath);

            // Recalculate topological order
            List<PluginDescriptor> refreshed = new ArrayList<>(getPlugins());
            List<PluginDescriptor> sorted = dependencyResolver.sort(refreshed);
            topology.clear();
            for (PluginDescriptor d : sorted) {
                topology.add(d.id());
            }
            return descriptor;
        } finally {
            managementLock.unlock();
        }
    }

    @Override
    public void startPlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            throw new PluginLoadException("插件不存在: " + pluginId);
        }
        startSinglePlugin(pluginId);
    }

    @Override
    public void stopPlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            throw new PluginLoadException("插件不存在: " + pluginId);
        }
        stopSinglePlugin(pluginId);
    }

    @Override
    public void unloadPlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            throw new PluginLoadException("Plugin does not exist: " + pluginId);
        }

        managementLock.lock();
        try {
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.ACTIVE) {
                stopSinglePlugin(pluginId);
            }
            unloadSinglePlugin(pluginId);
            topology.remove(pluginId);
        } finally {
            managementLock.unlock();
        }
    }

    @Override
    public void reloadPlugin(String pluginId, Path newJarPath) {
        PluginEntry oldEntry = plugins.get(pluginId);
        if (oldEntry == null) {
            throw new PluginLoadException("Plugin does not exist, cannot reload: " + pluginId);
        }

        // 1. Pre-load and validate new JAR descriptor
        PluginDescriptor newDescriptor = PluginDescriptorLoader.load(newJarPath);
        if (!pluginId.equals(newDescriptor.id())) {
            throw new PluginLoadException("Reload JAR plugin ID (" + newDescriptor.id()
                    + ") does not match target plugin ID (" + pluginId + ")");
        }

        // apiVersion pre-check
        if (!isApiVersionCompatible(newDescriptor.apiVersion())) {
            throw new PluginLoadException(
                    "Reload JAR apiVersion (" + newDescriptor.apiVersion()
                            + ") is incompatible with framework API version (" + SunsenVersion.API_VERSION + ")");
        }

        // Prefix conflict pre-check (exclude old plugin)
        List<PluginDescriptor> others = new ArrayList<>(getPlugins());
        others.removeIf(d -> d.id().equals(pluginId));
        others.add(newDescriptor);
        PluginClassLoader.validatePrefixes(others);

        // 2. Check if other plugins depend on this one
        for (PluginEntry entry : plugins.values()) {
            if (pluginId.equals(entry.descriptor().id())) continue;
            for (DependencyDescriptor dep : entry.descriptor().dependencies()) {
                if (pluginId.equals(dep.id())) {
                    throw new PluginLoadException(
                            "Plugin " + pluginId + " is depended on by plugin " + entry.descriptor().id()
                                    + ", cannot reload directly. Unload dependent first.");
                }
            }
        }

        managementLock.lock();
        try {
            // Phase 1: stop old plugin if ACTIVE (event published outside registry lock)
            if (stateMachine.getState(pluginId) == PluginState.ACTIVE) {
                stopSinglePlugin(pluginId);
            }

            // Phase 2: core unload without events
            PluginDescriptor oldDescriptor = doUnloadSinglePlugin(pluginId);

            // Phase 3: atomically unregister old extensions and register new ones inside write lock
            extensionRegistry.withWriteLock(() -> {
                stateMachine.init(newDescriptor.id(), PluginState.CREATED);
                loadSinglePluginCore(newDescriptor, newJarPath);
            });

            // Phase 4: update ClassLoader references for plugins that depend on the reloaded one
            for (PluginEntry entry : plugins.values()) {
                if (pluginId.equals(entry.descriptor().id())) continue;
                boolean dependsOnReloaded = entry.descriptor().dependencies().stream()
                        .anyMatch(dep -> pluginId.equals(dep.id()));
                if (dependsOnReloaded) {
                    List<PluginClassLoader> updated = new ArrayList<>();
                    for (DependencyDescriptor dep : entry.descriptor().dependencies()) {
                        PluginEntry depEntry = plugins.get(dep.id());
                        if (depEntry != null && depEntry.classLoader() != null) {
                            updated.add(depEntry.classLoader());
                        }
                    }
                    entry.classLoader().updateDependencyLoaders(updated);
                }
            }

            // Phase 5: start new plugin
            startSinglePlugin(pluginId);

            // Phase 6: publish events outside registry write lock to avoid dead lock
            if (oldDescriptor != null) {
                eventBus.publish(new PluginUnloadedEvent(oldDescriptor));
            }
            eventBus.publish(new PluginLoadedEvent(newDescriptor));
        } finally {
            managementLock.unlock();
        }
    }


    // 查询


    @Override
    public Optional<PluginDescriptor> getPlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        return Optional.ofNullable(entry).map(PluginEntry::descriptor);
    }

    @Override
    public List<PluginDescriptor> getPlugins() {
        return plugins.values().stream().map(PluginEntry::descriptor).toList();
    }

    @Override
    public PluginState getPluginState(String pluginId) {
        return stateMachine.getState(pluginId);
    }


    // 扩展访问


    @Override
    public <T> List<T> getExtensions(Class<T> extensionPoint) {
        return extensionRegistry.getExtensions(extensionPoint);
    }

    @Override
    public <T> Optional<T> getExtension(Class<T> extensionPoint, String extensionId) {
        return extensionRegistry.getExtension(extensionPoint, extensionId);
    }


    // 事件总线


    @Override
    public void publishEvent(PluginEvent event) {
        eventBus.publish(event);
    }

    @Override
    public <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener) {
        eventBus.subscribe(eventType, listener);
    }

    @Override
    public <T extends PluginEvent> void unsubscribe(Class<T> eventType, PluginEventListener<T> listener) {
        eventBus.unsubscribe(eventType, listener);
    }

    @Override
    public void setExtensionRegistrar(ExtensionRegistrar registrar) {
        this.extensionRegistrar = registrar;
        this.extensionRegistry.setExtensionRegistrar(registrar);
    }


    // 内部方法


    private void loadSinglePlugin(PluginDescriptor descriptor, Path jarPath) {
        loadSinglePluginCore(descriptor, jarPath);
        eventBus.publish(new PluginLoadedEvent(descriptor));
    }

    /**
     * Core loading logic without publishing events.
     */
    private void loadSinglePluginCore(PluginDescriptor descriptor, Path jarPath) {
        String pluginId = descriptor.id();

        // apiVersion compatibility check: major version must match
        if (!isApiVersionCompatible(descriptor.apiVersion())) {
            throw new PluginLoadException(
                    "Plugin " + pluginId + " apiVersion (" + descriptor.apiVersion()
                            + ") is incompatible with framework API version (" + SunsenVersion.API_VERSION + ")"
            );
        }

        // Create dependency plugin ClassLoader list
        List<PluginClassLoader> depLoaders = new ArrayList<>();
        for (DependencyDescriptor dep : descriptor.dependencies()) {
            PluginEntry depEntry = plugins.get(dep.id());
            if (depEntry != null && depEntry.classLoader() != null) {
                depLoaders.add(depEntry.classLoader());
            }
        }

        URL jarUrl;
        try {
            jarUrl = jarPath.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new PluginLoadException("Invalid JAR path: " + jarPath, e);
        }

        PluginClassLoader classLoader = new PluginClassLoader(
                new URL[]{jarUrl}, parentClassLoader, descriptor.packagePrefixes(), depLoaders
        );

        DefaultPluginContext[] contextHolder = new DefaultPluginContext[1];
        try {
            stateMachine.transition(pluginId, PluginState.CREATED, PluginState.RESOLVED);

            Plugin pluginInstance;
            try {
                Class<?> mainClass = classLoader.loadClass(descriptor.mainClass());
                pluginInstance = (Plugin) mainClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                stateMachine.forceSet(pluginId, PluginState.FAILED);
                throw new PluginLoadException("Cannot instantiate plugin main class: " + descriptor.mainClass(), e);
            }

            Path workDir = pluginsDir.resolve(pluginId);
            try {
                Files.createDirectories(workDir);
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, () -> "Cannot create work directory: " + workDir, e);
            }

            DefaultPluginContext context = new DefaultPluginContext(
                    descriptor, extensionRegistry, eventBus, this, workDir
            );
            contextHolder[0] = context;

            stateMachine.executePhase(() -> pluginInstance.onInit(context), descriptor, PluginState.RESOLVED);

            // Scan extensions
            extensionScanner.scan(descriptor, classLoader);

            plugins.put(pluginId, new PluginEntry(descriptor, pluginInstance, classLoader, context, jarPath));
            stateMachine.transition(pluginId, PluginState.RESOLVED, PluginState.LOADED);
        } catch (Exception e) {
            if (contextHolder[0] != null) {
                contextHolder[0].unsubscribeAll();
            }
            try {
                classLoader.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /**
     * 检查插件 apiVersion 是否与框架兼容.
     * 采用 SemVer 解析,只比较 major 版本号,minor 和 patch 级别的变更视为兼容.
     */
    private static boolean isApiVersionCompatible(String pluginApiVersion) {
        try {
            SemVer framework = SemVer.parse(SunsenVersion.API_VERSION);
            SemVer plugin = SemVer.parse(pluginApiVersion);
            return framework.major() == plugin.major();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void startSinglePlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) return;

        // LOADED(首次)和 STOPPED(重启)都可以进入 STARTING
        PluginState current = stateMachine.getState(pluginId);
        if (current != PluginState.LOADED && current != PluginState.STOPPED) {
            return;
        }
        stateMachine.transition(pluginId, current, PluginState.STARTING);
        try {
            stateMachine.executePhase(entry.plugin()::onStart, entry.descriptor(), PluginState.STARTING);
            stateMachine.transition(pluginId, PluginState.STARTING, PluginState.ACTIVE);
            eventBus.publish(new PluginStartedEvent(entry.descriptor()));
        } catch (Exception e) {
            // executePhase 已发布 PluginFailedEvent
        }
    }

    private void stopSinglePlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) return;

        stateMachine.transition(pluginId, PluginState.ACTIVE, PluginState.STOPPING);
        try {
            stateMachine.executePhase(entry.plugin()::onStop, entry.descriptor(), PluginState.STOPPING);
            stateMachine.transition(pluginId, PluginState.STOPPING, PluginState.STOPPED);
            eventBus.publish(new PluginStoppedEvent(entry.descriptor()));
        } catch (Exception e) {
            // executePhase 已发布 PluginFailedEvent
        }
    }

    private void unloadSinglePlugin(String pluginId) {
        PluginDescriptor descriptor = doUnloadSinglePlugin(pluginId);
        if (descriptor != null) {
            eventBus.publish(new PluginUnloadedEvent(descriptor));
        }
    }

    /**
     * Unload plugin core logic without publishing events.
     * Returns the plugin descriptor if unloaded, or null if plugin not found.
     */
    private PluginDescriptor doUnloadSinglePlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) return null;

        // 1. invoke onDestroy callback (plugin still visible, safe to access its own extensions)
        try {
            entry.plugin().onDestroy();
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "Plugin onDestroy error: " + pluginId, e);
        }

        // 2. complete unregistration and state transition inside ExtensionRegistry write lock
        extensionRegistry.withWriteLock(() -> {
            extensionRegistry.unregisterPlugin(pluginId);
            PluginState currentState = stateMachine.getState(pluginId);
            if (currentState != null && currentState != PluginState.UNLOADED) {
                stateMachine.transition(pluginId, currentState, PluginState.UNLOADED);
            }
            stateMachine.remove(pluginId);
        });

        // 3. cancel event subscriptions
        entry.context().unsubscribeAll();
        eventBus.unsubscribeAll(pluginId);

        // 4. remove from plugin map, completely invisible externally
        plugins.remove(pluginId);

        // 5. close ClassLoader, release JAR file handle
        try {
            entry.classLoader().close();
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "Close ClassLoader error: " + pluginId, e);
        }

        return entry.descriptor();
    }

    /**
     * 插件运行时条目,聚合插件实例,类加载器,上下文等核心对象
     *
     * @param descriptor  插件描述符
     * @param plugin      插件实例
     * @param classLoader 插件专用类加载器
     * @param context     插件运行时上下文
     * @param jarPath     插件 JAR 文件路径
     */
    private record PluginEntry(PluginDescriptor descriptor,
                               Plugin plugin,
                               PluginClassLoader classLoader,
                               DefaultPluginContext context,
                               Path jarPath) {
    }
}
