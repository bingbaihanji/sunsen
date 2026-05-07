package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginContext;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginManager;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
                System.getLogger(DefaultPluginContext.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "无法加载插件配置文件: " + configFile, e);
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
        return pluginManager;
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
