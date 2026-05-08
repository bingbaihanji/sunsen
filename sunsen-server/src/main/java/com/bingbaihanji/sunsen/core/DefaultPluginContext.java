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

/**
 * {@link PluginContext} 的默认实现
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
    // 插件配置属性
    private final Properties configProperties = new Properties();
    // 本上下文注册的事件订阅列表
    private final List<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * @param descriptor        插件描述符
     * @param extensionRegistry 扩展注册表
     * @param eventBus          事件总线
     * @param pluginManager     插件管理器
     * @param pluginWorkDir     插件工作目录
     */
    public DefaultPluginContext(PluginDescriptor descriptor,
                                ExtensionRegistry extensionRegistry,
                                PluginEventBus eventBus,
                                PluginManager pluginManager,
                                Path pluginWorkDir) {
        this.descriptor = descriptor;
        this.extensionRegistry = extensionRegistry;
        this.eventBus = eventBus;
        this.pluginManager = pluginManager;
        this.pluginWorkDir = pluginWorkDir;
        loadConfig();
    }

    // 加载配置文件
    private void loadConfig() {
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
        return configProperties.getProperty(key);
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        return configProperties.getProperty(key, defaultValue);
    }

    @Override
    public <T> List<T> getExtensions(Class<T> extensionPoint) {
        return extensionRegistry.getExtensions(extensionPoint);
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
        return new PluginManagerView(pluginManager);
    }

    /**
     * 事件订阅记录
     *
     * @param eventType 事件类型
     * @param listener  事件监听器
     */
    private record Subscription<T extends PluginEvent>(Class<T> eventType, PluginEventListener<T> listener) {
    }

    /**
     * PluginManager 的安全视图,限制插件只能执行查询操作,
     * 禁止直接加载/卸载/启动/停止/热重载其他插件.
     */
    private static class PluginManagerView implements PluginManager {

        private final PluginManager delegate;

        PluginManagerView(PluginManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void loadPlugins() {
            throw new PluginPermissionException("Plugin cannot invoke loadPlugins via PluginContext");
        }

        @Override
        public void startPlugins() {
            throw new PluginPermissionException("Plugin cannot invoke startPlugins via PluginContext");
        }

        @Override
        public void stopPlugins() {
            throw new PluginPermissionException("Plugin cannot invoke stopPlugins via PluginContext");
        }

        @Override
        public void unloadPlugins() {
            throw new PluginPermissionException("Plugin cannot invoke unloadPlugins via PluginContext");
        }

        @Override
        public PluginDescriptor loadPlugin(Path jarPath) {
            throw new PluginPermissionException("Plugin cannot invoke loadPlugin via PluginContext");
        }

        @Override
        public void startPlugin(String pluginId) {
            throw new PluginPermissionException("Plugin cannot invoke startPlugin via PluginContext");
        }

        @Override
        public void stopPlugin(String pluginId) {
            throw new PluginPermissionException("Plugin cannot invoke stopPlugin via PluginContext");
        }

        @Override
        public void unloadPlugin(String pluginId) {
            throw new PluginPermissionException("Plugin cannot invoke unloadPlugin via PluginContext");
        }

        @Override
        public void reloadPlugin(String pluginId, Path newJarPath) {
            throw new PluginPermissionException("Plugin cannot invoke reloadPlugin via PluginContext");
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
        public <T> List<T> getExtensions(Class<T> extensionPoint) {
            return delegate.getExtensions(extensionPoint);
        }

        @Override
        public <T> Optional<T> getExtension(Class<T> extensionPoint, String extensionId) {
            return delegate.getExtension(extensionPoint, extensionId);
        }

        @Override
        public void publishEvent(PluginEvent event) {
            delegate.publishEvent(event);
        }

        @Override
        public <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener) {
            delegate.subscribe(eventType, listener);
        }

        @Override
        public <T extends PluginEvent> void unsubscribe(Class<T> eventType, PluginEventListener<T> listener) {
            delegate.unsubscribe(eventType, listener);
        }

        @Override
        public void setExtensionRegistrar(ExtensionRegistrar registrar) {
            throw new PluginPermissionException("Plugin cannot invoke setExtensionRegistrar via PluginContext");
        }
    }
}
