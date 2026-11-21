package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.*;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PluginContext} 的默认实现
 *
 * @author 冰白寒祭
 * @date 2025
 */
public class DefaultPluginContext implements PluginContext {

    // 当前插件的描述符
    private final PluginDescriptor descriptor;
    // 扩展注册表,用于获取扩展实例
    private final ExtensionRegistry extensionRegistry;
    // 事件总线,用于发布与订阅事件
    private final PluginEventBus eventBus;
    // 插件管理器
    private final PluginManager pluginManager;
    // 插件专属工作目录
    private final Path pluginWorkDir;
    // 本上下文注册的事件订阅列表
    private final List<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();
    // 插件专属线程组,卸载时统一 interrupt
    private final ThreadGroup threadGroup;
    // 线程命名计数器
    private final AtomicInteger threadCounter = new AtomicInteger(0);
    // 插件配置属性
    private volatile Properties configProperties = new Properties();

    /**
     * @param descriptor        插件描述符
     * @param extensionRegistry 扩展注册表
     * @param eventBus          事件总线
     * @param pluginManager     插件管理器
     * @param pluginWorkDir     插件工作目录
     * @param threadGroup       插件专属线程组
     */
    public DefaultPluginContext(PluginDescriptor descriptor,
                                ExtensionRegistry extensionRegistry,
                                PluginEventBus eventBus,
                                PluginManager pluginManager,
                                Path pluginWorkDir,
                                ThreadGroup threadGroup) {
        this.descriptor = descriptor;
        this.extensionRegistry = extensionRegistry;
        this.eventBus = eventBus;
        this.pluginManager = pluginManager;
        this.pluginWorkDir = pluginWorkDir;
        this.threadGroup = threadGroup;
        Path configFile = pluginWorkDir.resolve("config.properties");
        if (Files.exists(configFile)) {
            try (InputStream is = Files.newInputStream(configFile)) {
                configProperties.load(is);
            } catch (IOException e) {
                throw new PluginLoadException("Failed to load plugin config: " + configFile, e);
            }
        }
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public Path getPluginWorkDir() {
        return pluginWorkDir;
    }

    @Override
    public String getProperty(String key) {
        String value = configProperties.getProperty(key);
        return value != null ? value : descriptor.mappings().get(key);
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        String value = configProperties.getProperty(key);
        if (value != null) {
            return value;
        }
        String mapping = descriptor.mappings().get(key);
        return mapping != null ? mapping : defaultValue;
    }

    @Override
    public synchronized void reloadConfig() {
        Properties fresh = new Properties();
        Path configFile = pluginWorkDir.resolve("config.properties");
        if (Files.exists(configFile)) {
            try (InputStream is = Files.newInputStream(configFile)) {
                fresh.load(is);
            } catch (IOException e) {
                throw new PluginLoadException("Failed to load plugin config: " + configFile, e);
            }
        }
        configProperties = fresh;
    }

    @Override
    public <T> List<T> getExtensions(Class<T> extensionPoint) {
        return extensionRegistry.getExtensions(extensionPoint);
    }

    @Override
    public <T> List<ExtensionInfo> getExtensionInfos(Class<T> extensionPoint) {
        return extensionRegistry.getExtensionInfos(extensionPoint);
    }

    @Override
    public void publishEvent(PluginEvent event) {
        if (!descriptor.id().equals(event.getSourcePluginId())) {
            throw new PluginLoadException(
                    "Plugin '" + descriptor.id() + "' cannot publish event with source plugin ID '"
                            + event.getSourcePluginId() + "'");
        }
        eventBus.publish(event);
    }

    @Override
    public <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener) {
        subscriptions.add(new Subscription<>(eventType, listener));
        eventBus.subscribe(eventType, listener, descriptor.id());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends PluginEvent> void unsubscribe(Class<T> eventType, PluginEventListener<T> listener) {
        subscriptions.removeIf(sub -> sub.eventType == eventType && sub.listener == listener);
        eventBus.unsubscribe(eventType, listener);
    }

    /**
     * 取消该插件通过本上下文注册的所有订阅
     */
    @SuppressWarnings("unchecked")
    public void unsubscribeAll() {
        for (Subscription<?> sub : subscriptions) {
            eventBus.unsubscribe((Class) sub.eventType, sub.listener);
        }
        subscriptions.clear();
    }

    @Override
    public PluginManager getPluginManager() {
        return new PluginManagerView(pluginManager, this);
    }

    @Override
    public ThreadFactory getThreadFactory() {
        String pluginId = descriptor.id();
        ClassLoader pluginClassLoader = Thread.currentThread().getContextClassLoader();
        return task -> {
            Thread t = new Thread(threadGroup, () -> {
                ClassLoader prev = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(pluginClassLoader);
                try {
                    task.run();
                } finally {
                    Thread.currentThread().setContextClassLoader(prev);
                }
            }, pluginId + "-thread-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 插件可见的 PluginManager 受限视图.
     * <p>
     * 默认只开放查询,扩展访问和绑定插件身份的事件能力.插件生命周期,安装卸载和注册器配置属于宿主管理能力,
     * 不通过默认上下文暴露给插件代码.
     */
    private static class PluginManagerView implements PluginManager {
        private static final String MANAGE_PERMISSION = "plugin:manage";
        private final PluginManager delegate;
        private final DefaultPluginContext context;

        PluginManagerView(PluginManager delegate, DefaultPluginContext context) {
            this.delegate = delegate;
            this.context = context;
        }

        @Override
        public void loadPlugins() {
            denyManage();
        }

        @Override
        public List<PluginLoadError> loadPluginsWithResult() {
            denyManage();
            return List.of();
        }

        @Override
        public void startPlugins() {
            denyManage();
        }

        @Override
        public void stopPlugins() {
            denyManage();
        }

        @Override
        public void unloadPlugins() {
            denyManage();
        }

        @Override
        public PluginDescriptor loadPlugin(Path jarPath) {
            denyManage();
            return null;
        }

        @Override
        public void startPlugin(String pluginId) {
            denyManage();
        }

        @Override
        public void stopPlugin(String pluginId) {
            denyManage();
        }

        @Override
        public void unloadPlugin(String pluginId) {
            denyManage();
        }

        @Override
        public void reloadPlugin(String pluginId, Path newJarPath) {
            denyManage();
        }

        @Override
        public void reloadPlugin(String pluginId) {
            denyManage();
        }

        @Override
        public void installPlugin(Path source) {
            denyManage();
        }

        @Override
        public void uninstallPlugin(String pluginId) {
            denyManage();
        }

        @Override
        public Optional<PluginDescriptor> getPlugin(String pluginId) {
            return delegate.getPlugin(pluginId);
        }

        @Override
        public List<PluginDescriptor> getPlugins() {
            return delegate.getPlugins();
        }

        @Override
        public PluginState getPluginState(String pluginId) {
            return delegate.getPluginState(pluginId);
        }

        @Override
        public <T> List<T> getExtensions(Class<T> ep) {
            return delegate.getExtensions(ep);
        }

        @Override
        public <T> Optional<T> getExtension(Class<T> ep, String id) {
            return delegate.getExtension(ep, id);
        }

        @Override
        public <T> List<ExtensionInfo> getExtensionInfos(Class<T> ep) {
            return delegate.getExtensionInfos(ep);
        }

        @Override
        public void publishEvent(PluginEvent event) {
            context.publishEvent(event);
        }

        @Override
        public <T extends PluginEvent> void subscribe(Class<T> et, PluginEventListener<T> l) {
            context.subscribe(et, l);
        }

        @Override
        public <T extends PluginEvent> void unsubscribe(Class<T> et, PluginEventListener<T> l) {
            context.unsubscribe(et, l);
        }

        @Override
        public void setExtensionRegistrar(ExtensionRegistrar r) {
            denyManage();
        }

        @Override
        public void close() { /* no-op: 插件不可关闭管理器 */ }

        private void denyManage() {
            throw new PluginPermissionException(
                    "插件 " + context.descriptor.id() + " 未被允许调用宿主管理能力: " + MANAGE_PERMISSION);
        }
    }

    /**
     * 事件订阅记录
     *
     * @param eventType 事件类型
     * @param listener  事件监听器
     */
    private record Subscription<T extends PluginEvent>(Class<T> eventType, PluginEventListener<T> listener) {
    }
}
