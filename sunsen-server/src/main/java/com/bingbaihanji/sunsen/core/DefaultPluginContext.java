package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.*;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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

    private static final Set<String> ALLOWED_METHODS = Set.of(
            "getPlugin", "getPlugins", "getPluginState", "isPluginActive",
            "getExtensions", "getExtension", "getExtensionInfos",
            "publishEvent", "subscribe", "unsubscribe", "close"
    );
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
    public synchronized String getProperty(String key) {
        return configProperties.getProperty(key);
    }

    @Override
    public synchronized String getProperty(String key, String defaultValue) {
        return configProperties.getProperty(key, defaultValue);
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
        return createManagerView();
    }

    @Override
    public ThreadFactory getThreadFactory() {
        String pluginId = descriptor.id();
        return task -> {
            Thread t = new Thread(threadGroup, task,
                    pluginId + "-thread-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @SuppressWarnings("unchecked")
    private PluginManager createManagerView() {
        final PluginManager delegate = pluginManager;
        final DefaultPluginContext context = this;
        return (PluginManager) java.lang.reflect.Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (!ALLOWED_METHODS.contains(name)) {
                        throw new PluginPermissionException(
                                "Plugin cannot invoke " + name + " via PluginContext");
                    }
                    return switch (name) {
                        case "publishEvent" -> {
                            context.publishEvent((PluginEvent) args[0]);
                            yield null;
                        }
                        case "subscribe" -> {
                            @SuppressWarnings("rawtypes")
                            Class rawType = (Class) args[0];
                            @SuppressWarnings("rawtypes")
                            PluginEventListener rawListener = (PluginEventListener) args[1];
                            context.subscribe(rawType, rawListener);
                            yield null;
                        }
                        case "unsubscribe" -> {
                            @SuppressWarnings("rawtypes")
                            Class rawType = (Class) args[0];
                            @SuppressWarnings("rawtypes")
                            PluginEventListener rawListener = (PluginEventListener) args[1];
                            context.unsubscribe(rawType, rawListener);
                            yield null;
                        }
                        case "close" -> null;
                        default -> method.invoke(delegate, args);
                    };
                }
        );
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
