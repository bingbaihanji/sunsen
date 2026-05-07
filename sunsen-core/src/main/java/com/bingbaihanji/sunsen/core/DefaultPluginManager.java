package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.*;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;
import com.bingbaihanji.sunsen.api.event.builtin.*;
import com.bingbaihanji.sunsen.core.lifecycle.LifecycleStateMachine;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link PluginManager} 的默认实现
 * <p>
 * 负责插件的批量/单例加载、启动、停止、卸载与热重载,整合扩展注册表、事件总线、
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
    private final List<String> topology = new CopyOnWriteArrayList<>();
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
            LOGGER.log(System.Logger.Level.WARNING, () -> "插件目录不存在: " + pluginsDir);
            return;
        }

        List<Path> jarPaths = new ArrayList<>();
        try (var stream = Files.list(pluginsDir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(jarPaths::add);
        } catch (IOException e) {
            throw new PluginLoadException("无法扫描插件目录: " + pluginsDir, e);
        }

        List<PluginDescriptor> descriptors = new ArrayList<>();
        Map<String, Path> descriptorToJar = new HashMap<>();
        for (Path jarPath : jarPaths) {
            try {
                PluginDescriptor descriptor = PluginDescriptorLoader.load(jarPath);
                descriptors.add(descriptor);
                descriptorToJar.put(descriptor.id(), jarPath);
                stateMachine.init(descriptor.id(), PluginState.CREATED);
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR, () -> "解析插件失败: " + jarPath, e);
            }
        }

        if (descriptors.isEmpty()) {
            return;
        }

        PluginClassLoader.validatePrefixes(descriptors);
        dependencyResolver.resolve(descriptors);
        List<PluginDescriptor> sorted = dependencyResolver.sort(descriptors);

        for (PluginDescriptor descriptor : sorted) {
            try {
                loadSinglePlugin(descriptor, descriptorToJar.get(descriptor.id()));
                topology.add(descriptor.id());
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR, () -> "加载插件失败: " + descriptor.id(), e);
                stateMachine.forceSet(descriptor.id(), PluginState.FAILED);
                eventBus.publish(new PluginFailedEvent(descriptor, PluginState.CREATED, e));
            }
        }
    }

    @Override
    public void startPlugins() {
        for (String pluginId : topology) {
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.LOADED) {
                startSinglePlugin(pluginId);
            }
        }
    }

    @Override
    public void stopPlugins() {
        List<String> reverse = new ArrayList<>(topology);
        Collections.reverse(reverse);
        for (String pluginId : reverse) {
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.ACTIVE) {
                stopSinglePlugin(pluginId);
            }
        }
    }

    @Override
    public void unloadPlugins() {
        List<String> reverse = new ArrayList<>(topology);
        Collections.reverse(reverse);
        for (String pluginId : reverse) {
            PluginState state = stateMachine.getState(pluginId);
            if (state == PluginState.STOPPED || state == PluginState.FAILED
                    || state == PluginState.LOADED) {
                unloadSinglePlugin(pluginId);
            }
        }
    }


    // 单插件操作


    @Override
    public PluginDescriptor loadPlugin(Path jarPath) {
        PluginDescriptor descriptor = PluginDescriptorLoader.load(jarPath);
        if (plugins.containsKey(descriptor.id())) {
            throw new PluginLoadException("插件已存在: " + descriptor.id());
        }
        // 将新插件与已加载插件合并后统一做前缀冲突校验及依赖版本校验
        List<PluginDescriptor> allDescriptors = new ArrayList<>(getPlugins());
        allDescriptors.add(descriptor);
        PluginClassLoader.validatePrefixes(allDescriptors);
        dependencyResolver.resolve(allDescriptors);

        stateMachine.init(descriptor.id(), PluginState.CREATED);
        loadSinglePlugin(descriptor, jarPath);

        // 重新计算拓扑序,确保新插件插入到正确位置
        List<PluginDescriptor> refreshed = new ArrayList<>(getPlugins());
        List<PluginDescriptor> sorted = dependencyResolver.sort(refreshed);
        topology.clear();
        for (PluginDescriptor d : sorted) {
            topology.add(d.id());
        }
        return descriptor;
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
            throw new PluginLoadException("插件不存在: " + pluginId);
        }
        unloadSinglePlugin(pluginId);
        topology.remove(pluginId);
    }

    @Override
    public void reloadPlugin(String pluginId, Path newJarPath) {
        PluginEntry oldEntry = plugins.get(pluginId);
        if (oldEntry == null) {
            throw new PluginLoadException("插件不存在,无法热重载: " + pluginId);
        }

        // 1. 预加载并校验新 JAR 描述符
        PluginDescriptor newDescriptor = PluginDescriptorLoader.load(newJarPath);
        if (!pluginId.equals(newDescriptor.id())) {
            throw new PluginLoadException("热重载 JAR 的插件 ID (" + newDescriptor.id()
                    + ") 与目标插件 ID (" + pluginId + ") 不一致");
        }

        // apiVersion 预校验
        if (!SunsenVersion.API_VERSION.equals(newDescriptor.apiVersion())) {
            throw new PluginLoadException(
                    "热重载 JAR 的 apiVersion (" + newDescriptor.apiVersion()
                            + ") 与框架 API 版本 (" + SunsenVersion.API_VERSION + ") 不兼容");
        }

        // 前缀冲突预校验(排除旧插件自身)
        List<PluginDescriptor> others = new ArrayList<>(getPlugins());
        others.removeIf(d -> d.id().equals(pluginId));
        others.add(newDescriptor);
        PluginClassLoader.validatePrefixes(others);

        // 2. 检查是否被其他插件依赖
        for (PluginEntry entry : plugins.values()) {
            if (pluginId.equals(entry.descriptor().id())) continue;
            for (DependencyDescriptor dep : entry.descriptor().dependencies()) {
                if (pluginId.equals(dep.id())) {
                    throw new PluginLoadException(
                            "插件 " + pluginId + " 被插件 " + entry.descriptor().id()
                                    + " 依赖,不能直接热重载请先卸载依赖方再重载");
                }
            }
        }

        // 3. 执行热替换
        extensionRegistry.withWriteLock(() -> {
            // stop(若处于 ACTIVE)
            if (stateMachine.getState(pluginId) == PluginState.ACTIVE) {
                stopSinglePlugin(pluginId);
            }
            // unload old(只要不是已卸载状态都执行)
            PluginState currentState = stateMachine.getState(pluginId);
            if (currentState != null && currentState != PluginState.UNLOADED) {
                unloadSinglePlugin(pluginId);
            }
            // load new
            stateMachine.init(newDescriptor.id(), PluginState.CREATED);
            loadSinglePlugin(newDescriptor, newJarPath);

            // 更新所有依赖该插件的其他插件的 ClassLoader 引用
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
        });

        // start
        startSinglePlugin(pluginId);
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
        String pluginId = descriptor.id();

        // apiVersion 兼容性校验
        String pluginApiVersion = descriptor.apiVersion();
        if (!SunsenVersion.API_VERSION.equals(pluginApiVersion)) {
            throw new PluginLoadException(
                    "插件 " + pluginId + " 的 apiVersion (" + pluginApiVersion
                            + ") 与框架 API 版本 (" + SunsenVersion.API_VERSION + ") 不兼容"
            );
        }

        // 创建依赖插件的 ClassLoader 列表
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
            throw new PluginLoadException("无效的 JAR 路径: " + jarPath, e);
        }

        PluginClassLoader classLoader = new PluginClassLoader(
                new URL[]{jarUrl}, parentClassLoader, descriptor.packagePrefixes(), depLoaders
        );

        stateMachine.transition(pluginId, PluginState.CREATED, PluginState.RESOLVED);

        Plugin pluginInstance;
        try {
            Class<?> mainClass = classLoader.loadClass(descriptor.mainClass());
            pluginInstance = (Plugin) mainClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            stateMachine.forceSet(pluginId, PluginState.FAILED);
            throw new PluginLoadException("无法实例化插件主类: " + descriptor.mainClass(), e);
        }

        Path workDir = pluginsDir.resolve(pluginId);
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "无法创建工作目录: " + workDir, e);
        }

        DefaultPluginContext context = new DefaultPluginContext(
                descriptor, extensionRegistry, eventBus, this, workDir
        );

        stateMachine.executePhase(() -> pluginInstance.onInit(context), descriptor, PluginState.RESOLVED);

        // 扫描扩展
        extensionScanner.scan(descriptor, classLoader);

        plugins.put(pluginId, new PluginEntry(descriptor, pluginInstance, classLoader, context, jarPath));
        stateMachine.transition(pluginId, PluginState.RESOLVED, PluginState.LOADED);
        eventBus.publish(new PluginLoadedEvent(descriptor));
    }

    private void startSinglePlugin(String pluginId) {
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) return;

        stateMachine.transition(pluginId, PluginState.LOADED, PluginState.STARTING);
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
        PluginEntry entry = plugins.get(pluginId);
        if (entry == null) return;

        // 1. 先转换状态,让外部查询立即感知插件已进入 UNLOADED
        PluginState currentState = stateMachine.getState(pluginId);
        stateMachine.transition(pluginId, currentState, PluginState.UNLOADED);

        // 2. 注销扩展(在 onDestroy 前完成,避免其他线程在销毁窗口内获取到将要失效的扩展)
        extensionRegistry.unregisterPlugin(pluginId);

        // 3. 调用插件销毁回调
        try {
            entry.plugin().onDestroy();
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "插件 onDestroy 异常: " + pluginId, e);
        }

        // 4. 取消事件订阅
        entry.context().unsubscribeAll();
        eventBus.unsubscribeAll(pluginId);

        // 5. 从插件映射表中移除,对外完全不可见
        plugins.remove(pluginId);

        eventBus.publish(new PluginUnloadedEvent(entry.descriptor()));

        // 6. 关闭 ClassLoader,释放 JAR 文件句柄
        try {
            entry.classLoader().close();
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, () -> "关闭插件 ClassLoader 异常: " + pluginId, e);
        }

        // 7. 清理状态机条目,避免内存泄漏
        stateMachine.remove(pluginId);
    }

    /**
     * 插件运行时条目,聚合插件实例、类加载器、上下文等核心对象
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
