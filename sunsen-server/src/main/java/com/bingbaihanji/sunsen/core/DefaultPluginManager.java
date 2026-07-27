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
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link PluginManager} 的默认实现
 * <p>
 * 负责插件的批量/单例加载,启动,停止,卸载与热重载,整合扩展注册表,事件总线,
 * 依赖解析器与生命周期状态机,提供完整的插件生命周期管理能力
 *
 * @author 冰白寒祭
 * @date 2025
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
    // 插件生命周期阶段超时(毫秒),默认 30 秒,0 表示无超时
    private long lifecycleTimeoutMs = 30_000;
    // STARTING 状态排空等待时间(毫秒),默认 5 秒
    private long startingDrainTimeoutMs = 5_000;

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

    /**
     * 获取插件根目录
     */
    public Path getPluginsDir() {
        return pluginsDir;
    }

    /**
     * 设置插件根目录
     *
     * @param pluginsDir 插件目录路径
     */
    public void setPluginsDir(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }


    // 批量操作

    /**
     * 设置父 ClassLoader
     *
     * @param parentClassLoader 父类加载器
     */
    public void setParentClassLoader(ClassLoader parentClassLoader) {
        this.parentClassLoader = parentClassLoader;
    }

    /**
     * 设置插件生命周期阶段超时(毫秒),0 表示无超时.
     * 适用于 onInit / onStart / onStop / onDestroy 回调.
     */
    public void setLifecycleTimeoutMs(long timeoutMs) {
        this.lifecycleTimeoutMs = timeoutMs;
    }

    /**
     * 设置 STARTING 状态排空等待时间(毫秒).
     * 卸载时若插件正在 STARTING,先等待此时间让 onStart 完成,超时后强制清理.
     */
    public void setStartingDrainTimeoutMs(long timeoutMs) {
        this.startingDrainTimeoutMs = timeoutMs;
    }

    /**
     * 等待 STARTING 状态的插件完成启动或超时.
     * 若 onStart 正常完成(→ACTIVE)则执行正常停 止;
     * 若超时仍为 STARTING 则强制置为 STOPPED.
     */
    private void drainStarting(String pluginId) {
        long deadline = System.currentTimeMillis() + startingDrainTimeoutMs;
        while (stateMachine.getState(pluginId) == PluginState.STARTING
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        PluginState current = stateMachine.getState(pluginId);
        if (current == PluginState.ACTIVE) {
            stopSinglePlugin(pluginId);
        } else if (current == PluginState.STARTING) {
            // 仍然在启动中,强制跳过 onStop 直接准备卸载
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "插件 " + pluginId + " onStart 超时,强制终止");
            stateMachine.forceSet(pluginId, PluginState.STOPPED);
        }
    }

    /**
     * 在线程中执行任务并等待,超时后中断线程.
     * 任务抛出的异常会被捕获并重新抛出.
     */
    private void executeWithTimeout(Runnable task, String pluginId, String phaseName) {
        executeWithTimeout(task, pluginId, phaseName, null);
    }

    private void executeWithTimeout(Runnable task, String pluginId, String phaseName, ClassLoader pluginClassLoader) {
        Runnable wrappedTask = pluginClassLoader != null ? () -> {
            ClassLoader prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(pluginClassLoader);
            try {
                task.run();
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
        } : task;

        if (lifecycleTimeoutMs <= 0) {
            wrappedTask.run();
            return;
        }
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = Thread.ofPlatform().daemon().start(() -> {
            try {
                wrappedTask.run();
            } catch (Throwable e) {
                error.set(e);
            }
        });
        try {
            t.join(lifecycleTimeoutMs);
            if (t.isAlive()) {
                t.interrupt();
                throw new PluginLoadException("插件 " + pluginId + " " + phaseName + " 超时 (" + lifecycleTimeoutMs + "ms)");
            }
            Throwable captured = error.get();
            if (captured != null) {
                if (captured instanceof RuntimeException re) {
                    throw re;
                }
                if (captured instanceof Error err) {
                    throw err;
                }
                throw new PluginLoadException("插件 " + pluginId + " " + phaseName + " 异常", captured);
            }
        } catch (InterruptedException e) {
            t.interrupt();
            Thread.currentThread().interrupt();
            throw new PluginLoadException("插件 " + pluginId + " " + phaseName + " 被中断");
        }
    }

    @Override
    public void loadPlugins() {
        loadPluginsInternal();
    }

    @Override
    public List<PluginLoadError> loadPluginsWithResult() {
        return loadPluginsInternal();
    }

    /**
     * 共用加载逻辑,返回所有加载失败项.
     */
    private List<PluginLoadError> loadPluginsInternal() {
        List<PluginLoadError> errors = new ArrayList<>();

        if (!Files.exists(pluginsDir)) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "Plugin directory not found: " + pluginsDir);
            return errors;
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
                    PluginLoadException ex = new PluginLoadException(
                            "Duplicate plugin ID: '" + descriptor.id() + "' found in " + jarPath);
                    errors.add(new PluginLoadError(descriptor.id(), jarPath, ex));
                    LOGGER.log(System.Logger.Level.ERROR, () -> "Parse plugin failed: " + jarPath, ex);
                    continue;
                }
                if (plugins.containsKey(descriptor.id())) {
                    LOGGER.log(System.Logger.Level.INFO,
                            () -> "Plugin already loaded, skip duplicate scan entry: " + jarPath);
                    continue;
                }
                descriptors.add(descriptor);
                descriptorToJar.put(descriptor.id(), jarPath);
                stateMachine.init(descriptor.id(), PluginState.CREATED);
            } catch (Exception e) {
                if (e instanceof IllegalStateException && e.getMessage() != null
                        && e.getMessage().contains("已在状态机中注册")) {
                    LOGGER.log(System.Logger.Level.INFO,
                            () -> "Plugin already being loaded, skip duplicate scan entry: " + jarPath);
                    continue;
                }
                errors.add(new PluginLoadError(jarPath.getFileName().toString(), jarPath, e));
                LOGGER.log(System.Logger.Level.ERROR, () -> "Parse plugin failed: " + jarPath, e);
            }
        }

        if (descriptors.isEmpty()) {
            return errors;
        }

        managementLock.lock();
        try {
            // 锁内：防止并发 loadPlugins/loadPlugin 导致的 ClassLoader 泄漏
            try {
                List<PluginDescriptor> allDescriptors = new ArrayList<>(getPlugins());
                allDescriptors.addAll(descriptors);
                PluginClassLoader.validatePrefixes(allDescriptors);
                dependencyResolver.resolve(allDescriptors);
            } catch (Exception e) {
                for (PluginDescriptor d : descriptors) {
                    stateMachine.remove(d.id());
                }
                throw e;
            }
            List<List<PluginDescriptor>> waves = dependencyResolver.sortByLevels(descriptors);

            for (List<PluginDescriptor> wave : waves) {
                List<Thread> waveThreads = new ArrayList<>(wave.size());
                List<PluginLoadError> waveErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

                for (PluginDescriptor descriptor : wave) {
                    Path jarPath = descriptorToJar.get(descriptor.id());
                    Thread t = Thread.ofVirtual().start(() -> {
                        try {
                            loadSinglePlugin(descriptor, jarPath);
                        } catch (Exception e) {
                            waveErrors.add(new PluginLoadError(descriptor.id(), jarPath, e));
                            LOGGER.log(System.Logger.Level.ERROR, () -> "Load plugin failed: " + descriptor.id(), e);
                            // 仅当状态机尚未置为 FAILED 时发布事件(executePhase 已处理的场合避免重复)
                            if (stateMachine.getState(descriptor.id()) != PluginState.FAILED) {
                                stateMachine.forceSet(descriptor.id(), PluginState.FAILED);
                                eventBus.publish(new PluginFailedEvent(descriptor, PluginState.CREATED, e));
                            }
                        }
                    });
                    waveThreads.add(t);
                }

                for (Thread t : waveThreads) {
                    try {
                        t.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new PluginLoadException("Interrupted while waiting for plugin wave to load", e);
                    }
                }

                errors.addAll(waveErrors);

                for (PluginDescriptor descriptor : wave) {
                    if (stateMachine.getState(descriptor.id()) == PluginState.LOADED) {
                        topology.add(descriptor.id());
                    }
                }
            }
        } finally {
            managementLock.unlock();
        }

        return errors;
    }

    @Override
    public void startPlugins() {
        List<String> snapshot;
        managementLock.lock();
        try {
            snapshot = List.copyOf(topology);
        } finally {
            managementLock.unlock();
        }
        for (String pluginId : snapshot) {
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.LOADED) {
                try {
                    startSinglePlugin(pluginId);
                } catch (Exception e) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            () -> "Failed to start plugin: " + pluginId, e);
                }
            }
        }
    }

    @Override
    public void stopPlugins() {
        List<String> snapshot;
        managementLock.lock();
        try {
            snapshot = new ArrayList<>(topology);
        } finally {
            managementLock.unlock();
        }
        Collections.reverse(snapshot);
        for (String pluginId : snapshot) {
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.ACTIVE) {
                try {
                    stopSinglePlugin(pluginId);
                } catch (Exception e) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            () -> "Failed to stop plugin: " + pluginId, e);
                }
            }
        }
    }


    // 单插件操作

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
                if (state == PluginState.ACTIVE) {
                    stopSinglePlugin(pluginId);
                } else if (state == PluginState.STARTING) {
                    drainStarting(pluginId);
                }
                // 反向拓扑序保证依赖方先卸载,checkNoDependents 作为安全网
                checkNoDependents(pluginId);
                unloadSinglePlugin(pluginId);
            }
            topology.clear();
        } finally {
            managementLock.unlock();
        }
    }

    @Override
    public PluginDescriptor loadPlugin(Path jarPath) {
        PluginDescriptor descriptor = PluginDescriptorLoader.load(jarPath);

        // Combine new plugin with already loaded ones for prefix and dependency validation (outside lock, read-only)
        List<PluginDescriptor> allDescriptors = new ArrayList<>(getPlugins());
        allDescriptors.add(descriptor);
        PluginClassLoader.validatePrefixes(allDescriptors);
        dependencyResolver.resolve(allDescriptors);

        managementLock.lock();
        try {
            // Double-check inside lock to avoid TOCTOU race
            if (plugins.containsKey(descriptor.id())) {
                throw new PluginLoadException("Plugin already exists: " + descriptor.id());
            }
            // Init state machine inside lock so a failed check never leaves orphaned state
            stateMachine.init(descriptor.id(), PluginState.CREATED);
            try {
                loadSinglePlugin(descriptor, jarPath);
            } catch (Exception e) {
                stateMachine.remove(descriptor.id());
                throw e;
            }
            rebuildTopology();
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
        PluginState state = stateMachine.getState(pluginId);
        if (state != PluginState.ACTIVE) {
            throw new PluginLoadException("插件状态不是 ACTIVE,无法停止: " + pluginId + " (当前状态: " + state + ")");
        }
        stopSinglePlugin(pluginId);
    }

    @Override
    public void unloadPlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            throw new PluginLoadException("插件不存在: " + pluginId);
        }

        managementLock.lock();
        try {
            checkNoDependents(pluginId);
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.ACTIVE) {
                stopSinglePlugin(pluginId);
            } else if (state == PluginState.STARTING) {
                drainStarting(pluginId);
            }
            unloadSinglePlugin(pluginId);
            rebuildTopology();
        } finally {
            managementLock.unlock();
        }
    }


    // 查询

    @Override
    public void reloadPlugin(String pluginId, Path newJarPath) {
        // Lock-external: pure I/O and data validation only
        PluginDescriptor newDescriptor = PluginDescriptorLoader.load(newJarPath);
        if (!pluginId.equals(newDescriptor.id())) {
            throw new PluginLoadException("Reload JAR plugin ID (" + newDescriptor.id()
                    + ") does not match target plugin ID (" + pluginId + ")");
        }
        if (!isApiVersionCompatible(newDescriptor.apiVersion())) {
            throw new PluginLoadException(
                    "Reload JAR apiVersion (" + newDescriptor.apiVersion()
                            + ") is incompatible with framework API version (" + SunsenVersion.API_VERSION + ")");
        }

        managementLock.lock();
        try {
            // Re-check shared state inside lock to avoid TOCTOU race
            PluginEntry oldEntry = plugins.get(pluginId);
            if (oldEntry == null) {
                throw new PluginLoadException("插件不存在, cannot reload: " + pluginId);
            }

            // Prefix conflict check (exclude old plugin)
            List<PluginDescriptor> others = new ArrayList<>(getPlugins());
            others.removeIf(d -> d.id().equals(pluginId));
            others.add(newDescriptor);
            PluginClassLoader.validatePrefixes(others);

            // Check if other plugins depend on this one
            checkNoDependents(pluginId);

            // Phase 1: stop old plugin if ACTIVE (event published outside registry lock)
            if (stateMachine.getState(pluginId) == PluginState.ACTIVE) {
                stopSinglePlugin(pluginId);
            }

            // Phase 2: cleanup old plugin instance and atomically swap extension registry entries.
            Path oldJarPath = oldEntry.jarPath();
            PluginDescriptor oldDescriptor = oldEntry.descriptor();

            // Phase 3: atomically unregister old extensions, destroy old instance and register new extensions
            // inside a SINGLE write lock — readers never observe destroyed old extensions or an empty gap.
            try {
                extensionRegistry.withWriteLock(() -> {
                    unregisterPluginFromRegistry(pluginId);
                    cleanupPluginInstance(pluginId);
                    stateMachine.init(newDescriptor.id(), PluginState.CREATED);
                    loadSinglePluginCore(newDescriptor, newJarPath);
                });
            } catch (Exception loadException) {
                // Rollback: try to restore old plugin from its original JAR
                LOGGER.log(System.Logger.Level.WARNING,
                        () -> "Plugin reload failed for " + pluginId + ", attempting rollback to " + oldJarPath,
                        loadException);
                boolean rolledBack = false;
                if (oldDescriptor != null && oldJarPath != null && Files.exists(oldJarPath)) {
                    try {
                        extensionRegistry.withWriteLock(() -> {
                            stateMachine.remove(pluginId);
                            stateMachine.init(pluginId, PluginState.CREATED);
                            loadSinglePluginCore(oldDescriptor, oldJarPath);
                        });
                        startSinglePlugin(pluginId);
                        if (stateMachine.getState(pluginId) == PluginState.ACTIVE) {
                            rolledBack = true;
                            LOGGER.log(System.Logger.Level.INFO,
                                    () -> "Plugin " + pluginId + " successfully rolled back to " + oldJarPath);
                        } else {
                            LOGGER.log(System.Logger.Level.ERROR,
                                    () -> "Rollback of plugin " + pluginId
                                            + " loaded but failed to start (state="
                                            + stateMachine.getState(pluginId) + ")");
                        }
                    } catch (Exception rollbackEx) {
                        LOGGER.log(System.Logger.Level.ERROR,
                                () -> "Rollback failed for plugin " + pluginId, rollbackEx);
                        stateMachine.forceSet(pluginId, PluginState.FAILED);
                        if (oldDescriptor != null) {
                            eventBus.publish(new PluginFailedEvent(oldDescriptor, PluginState.LOADED, rollbackEx));
                        }
                    }
                }
                if (!rolledBack && oldDescriptor != null) {
                    eventBus.publish(new PluginUnloadedEvent(oldDescriptor));
                }
                throw new PluginLoadException("Plugin reload failed: " + pluginId, loadException);
            }

            // Phase 4: publish lifecycle events outside registry write lock to avoid deadlock.
            // Order must match normal load: Unloaded(old) → Loaded(new) → Started(new) → Reloaded.
            if (oldDescriptor != null) {
                eventBus.publish(new PluginUnloadedEvent(oldDescriptor));
            }
            eventBus.publish(new PluginLoadedEvent(newDescriptor));

            // Phase 5: start new plugin (publishes PluginStartedEvent)
            startSinglePlugin(pluginId);
            rebuildTopology();

            // Phase 6: signal reload complete — only when plugin actually reached ACTIVE.
            PluginState finalState = stateMachine.getState(pluginId);
            if (finalState != PluginState.ACTIVE) {
                throw new PluginLoadException(
                        "Plugin reload completed but new version failed to start: " + pluginId
                                + " (state=" + finalState + ")");
            }
            eventBus.publish(new PluginReloadedEvent(oldDescriptor, newDescriptor));
        } finally {
            managementLock.unlock();
        }
    }

    @Override
    public Optional<PluginDescriptor> getPlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        return Optional.ofNullable(entry).map(PluginEntry::descriptor);
    }

    @Override
    public List<PluginDescriptor> getPlugins() {
        return plugins.values().stream().map(PluginEntry::descriptor).toList();
    }


    // 扩展访问

    @Override
    public PluginState getPluginState(String pluginId) {
        return stateMachine.getState(pluginId);
    }

    @Override
    public <T> List<T> getExtensions(Class<T> extensionPoint) {
        return extensionRegistry.getExtensions(extensionPoint);
    }

    @Override
    public <T> Optional<T> getExtension(Class<T> extensionPoint, String extensionId) {
        return extensionRegistry.getExtension(extensionPoint, extensionId);
    }

    @Override
    public <T> List<ExtensionInfo> getExtensionInfos(Class<T> extensionPoint) {
        return extensionRegistry.getExtensionInfos(extensionPoint);
    }

    @Override
    public void reloadPlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            throw new PluginLoadException("插件不存在, cannot reload: " + pluginId);
        }
        reloadPlugin(pluginId, entry.jarPath());
    }

    @Override
    public void installPlugin(Path source) {
        if (!Files.exists(source)) {
            throw new PluginLoadException("Source JAR does not exist: " + source);
        }
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            throw new PluginLoadException("Cannot create plugins directory: " + pluginsDir, e);
        }

        Path dest = pluginsDir.resolve(source.getFileName());
        try {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new PluginLoadException("Cannot copy plugin JAR to " + dest, e);
        }

        // 先解析描述符获取 ID,用于失败回滚
        final String pluginId = PluginDescriptorLoader.load(dest).id();
        PluginDescriptor descriptor;
        try {
            descriptor = loadPlugin(dest);
            startPlugin(pluginId);
        } catch (Exception e) {
            // 先完整卸载已加载的插件(内存回滚),再删除 JAR 文件
            if (getPlugin(pluginId).isPresent()) {
                try {
                    unloadPlugin(pluginId);
                } catch (Exception unloadEx) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            () -> "installPlugin: failed to unload after start failure: " + pluginId, unloadEx);
                }
            }
            try {
                Files.deleteIfExists(dest);
            } catch (IOException deleteEx) {
                LOGGER.log(System.Logger.Level.WARNING,
                        () -> "installPlugin: failed to delete copied JAR after failure: " + dest, deleteEx);
            }
            throw e;
        }

        eventBus.publish(new PluginInstalledEvent(descriptor, dest));
    }

    @Override
    public void uninstallPlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            throw new PluginLoadException("插件不存在, cannot uninstall: " + pluginId);
        }
        PluginDescriptor descriptor = entry.descriptor();
        Path jarPath = entry.jarPath();

        managementLock.lock();
        try {
            checkNoDependents(pluginId);
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.ACTIVE) {
                stopSinglePlugin(pluginId);
            }
            unloadSinglePlugin(pluginId);
            rebuildTopology();
        } finally {
            managementLock.unlock();
        }

        // Delete JAR after ClassLoader is closed and all references are released
        try {
            Files.deleteIfExists(jarPath);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "Cannot delete plugin JAR: " + jarPath + " for plugin " + pluginId, e);
        }

        eventBus.publish(new PluginUninstalledEvent(descriptor));
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


    // 内部方法

    @Override
    public void setExtensionRegistrar(ExtensionRegistrar registrar) {
        this.extensionRegistrar = registrar;
        this.extensionRegistry.setExtensionRegistrar(registrar);
    }

    @Override
    public void close() {
        try {
            stopPlugins();
        } finally {
            unloadPlugins();
            eventBus.close();
        }
    }

    /**
     * 根据当前 plugins 快照重建拓扑顺序.
     * 调用方必须持有 managementLock.
     */
    private void rebuildTopology() {
        List<PluginDescriptor> current = new ArrayList<>(getPlugins());
        topology.clear();
        if (current.isEmpty()) {
            return;
        }
        for (PluginDescriptor d : dependencyResolver.sort(current)) {
            topology.add(d.id());
        }
    }

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
            } else if (!dep.optional()) {
                PluginState depState = stateMachine.getState(dep.id());
                String reason = depState == PluginState.FAILED
                        ? "required dependency '" + dep.id() + "' failed to load"
                        : "required dependency '" + dep.id() + "' is not loaded";
                throw new PluginLoadException("Cannot load plugin '" + pluginId + "': " + reason);
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

            ThreadGroup threadGroup = new ThreadGroup("plugin-" + pluginId);
            DefaultPluginContext context = new DefaultPluginContext(
                    descriptor, extensionRegistry, eventBus, this, workDir, threadGroup
            );
            contextHolder[0] = context;

            stateMachine.executePhase(
                    () -> executeWithTimeout(() -> pluginInstance.onInit(context), pluginId, "onInit", classLoader),
                    descriptor, PluginState.RESOLVED);

            // Scan extensions
            extensionScanner.scan(descriptor, classLoader);

            PluginEntry newEntry = new PluginEntry(descriptor, pluginInstance, classLoader, context, jarPath, threadGroup);
            PluginEntry existing = plugins.putIfAbsent(pluginId, newEntry);
            if (existing != null) {
                // 并发加载同 ID 插件,关闭冗余 ClassLoader 避免泄漏
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                }
                throw new PluginLoadException("插件 " + pluginId + " 已被并发加载");
            }
            stateMachine.transition(pluginId, PluginState.RESOLVED, PluginState.LOADED);
        } catch (Exception e) {
            // 清理已注册的扩展，防止残留"死实例"（ClassLoader 即将关闭）
            extensionRegistry.unregisterPlugin(pluginId);
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

    private void startSinglePlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            return;
        }

        // LOADED(首次)和 STOPPED(重启)都可以进入 STARTING
        PluginState current = stateMachine.getState(pluginId);
        if (current != PluginState.LOADED && current != PluginState.STOPPED) {
            return;
        }
        stateMachine.transition(pluginId, current, PluginState.STARTING);
        stateMachine.executePhase(
                () -> executeWithTimeout(entry.plugin()::onStart, pluginId, "onStart", entry.classLoader()),
                entry.descriptor(), PluginState.STARTING);
        stateMachine.transition(pluginId, PluginState.STARTING, PluginState.ACTIVE);
        eventBus.publish(new PluginStartedEvent(entry.descriptor()));
    }

    private void stopSinglePlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            return;
        }

        stateMachine.transition(pluginId, PluginState.ACTIVE, PluginState.STOPPING);
        stateMachine.executePhase(
                () -> executeWithTimeout(entry.plugin()::onStop, pluginId, "onStop", entry.classLoader()),
                entry.descriptor(), PluginState.STOPPING);
        stateMachine.transition(pluginId, PluginState.STOPPING, PluginState.STOPPED);
        eventBus.publish(new PluginStoppedEvent(entry.descriptor()));
    }

    private void unloadSinglePlugin(String pluginId) {
        PluginDescriptor descriptor = doUnloadSinglePlugin(pluginId);
        if (descriptor != null) {
            eventBus.publish(new PluginUnloadedEvent(descriptor));
        }
    }

    /**
     * 检查是否有其他已加载插件依赖给定插件.
     * 有依赖者时抛出 PluginLoadException,防止 ClassLoader 泄漏.
     */
    private void checkNoDependents(String pluginId) {
        for (PluginEntry entry : plugins.values()) {
            if (pluginId.equals(entry.descriptor().id())) {
                continue;
            }
            for (DependencyDescriptor dep : entry.descriptor().dependencies()) {
                if (pluginId.equals(dep.id())) {
                    throw new PluginLoadException(
                            "插件 " + pluginId + " 被插件 " + entry.descriptor().id()
                                    + " 依赖，无法直接卸载。请先卸载依赖方。");
                }
            }
        }
    }

    /**
     * 清理插件实例资源(onDestroy, 中断线程组, 取消订阅, 移除映射, 关闭 ClassLoader).
     * 不涉及 ExtensionRegistry 操作,调用方需自行管理注册表锁以保证原子性.
     *
     * @return 被清理的插件描述符,插件不存在时返回 null
     */
    private PluginDescriptor cleanupPluginInstance(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            return null;
        }

        // 1. invoke onDestroy callback (with timeout protection)
        try {
            executeWithTimeout(entry.plugin()::onDestroy, pluginId, "onDestroy", entry.classLoader());
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "Plugin onDestroy error: " + pluginId, e);
        }

        // 2. interrupt all threads created via the plugin's ThreadFactory
        entry.threadGroup().interrupt();

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
     * 在 ExtensionRegistry 写锁内注销扩展并完成状态转换.
     * 调用方必须已持有 {@code extensionRegistry} 的写锁.
     */
    private void unregisterPluginFromRegistry(String pluginId) {
        extensionRegistry.unregisterPlugin(pluginId);
        PluginState currentState = stateMachine.getState(pluginId);
        if (currentState != null && currentState != PluginState.UNLOADED) {
            stateMachine.transition(pluginId, currentState, PluginState.UNLOADED);
        }
        stateMachine.remove(pluginId);
    }

    /**
     * 卸载单个插件的完整流程(注册表注销 + 清理实例),不发布事件.
     * 返回被卸载的插件描述符,插件不存在时返回 null.
     */
    private PluginDescriptor doUnloadSinglePlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) {
            return null;
        }
        PluginDescriptor descriptor = entry.descriptor();

        // Phase A: make extensions invisible before plugin instance is destroyed.
        extensionRegistry.withWriteLock(() -> unregisterPluginFromRegistry(pluginId));

        // Phase B: lock-external cleanup (onDestroy, interrupt, unsubscribe, remove, close ClassLoader)
        cleanupPluginInstance(pluginId);

        return descriptor;
    }

    /**
     * 插件运行时条目,聚合插件实例,类加载器,上下文等核心对象
     *
     * @param descriptor  插件描述符
     * @param plugin      插件实例
     * @param classLoader 插件专用类加载器
     * @param context     插件运行时上下文
     * @param jarPath     插件 JAR 文件路径
     * @param threadGroup 插件专属线程组,卸载时用于统一中断
     */
    private record PluginEntry(PluginDescriptor descriptor,
                               Plugin plugin,
                               PluginClassLoader classLoader,
                               DefaultPluginContext context,
                               Path jarPath,
                               ThreadGroup threadGroup) {
    }
}
