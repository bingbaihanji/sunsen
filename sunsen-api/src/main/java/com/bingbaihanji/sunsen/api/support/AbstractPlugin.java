package com.bingbaihanji.sunsen.api.support;

import com.bingbaihanji.sunsen.api.*;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 所有插件入口必须继承此抽象类
 * 插件抽象基类,对 {@link Plugin} 接口的便利封装.
 * <p>
 * 插件开发者继承此类而非直接实现 {@link Plugin},主要好处:
 * <ul>
 *   <li>用 {@link #onInitialized()} 替代 {@link #onInit},上下文已注入,可直接调用保护方法</li>
 *   <li>通过 {@link #manage(AutoCloseable)} 注册托管资源,{@code onDestroy} 时自动按注册逆序关闭</li>
 *   <li>内置权限检查,扩展查询,状态查询等常用辅助方法</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * public class OrderPlugin extends AbstractPlugin {
 *
 *     private ManagedScheduler scheduler;
 *
 *     @Override
 *     protected void onInitialized() {
 *         requirePermission("event:publish");
 *         PluginProperties props = PluginProperties.of(context());
 *         int interval = props.getInt("poll.interval", 30);
 *     }
 *
 *     @Override
 *     public void onStart() {
 *         scheduler = manage(ManagedScheduler.create(pluginId()));
 *         scheduler.scheduleWithFixedDelay(this::poll, 0, 30, TimeUnit.SECONDS);
 *         subscribe(PaymentEvent.class, this::onPayment);
 *     }
 *
 *     @Override
 *     public void onStop() {
 *         scheduler.shutdown();   // 优雅停止任务
 *     }
 *     // onDestroy 无需覆盖:托管资源会被自动关闭,context 会被自动清空
 * }
 * }</pre>
 */
public abstract class AbstractPlugin implements Plugin {

    /**
     * onDestroy 时需要关闭的托管资源,按注册逆序关闭(LIFO).
     * 所有生命周期方法由框架顺序调用,无需线程安全容器.
     */
    private final List<AutoCloseable> managedResources = new ArrayList<>();
    private PluginContext context;

    // ---- 生命周期 ----

    /**
     * 框架注入上下文,子类不应覆盖此方法,覆盖 {@link #onInitialized()} 代替.
     */
    @Override
    public final void onInit(PluginContext context) {
        this.context = context;
        onInitialized();
    }

    /**
     * 上下文注入完成后的初始化回调.
     * <p>
     * 在此读取配置,准备数据结构.禁止启动后台线程或注册事件监听器(应在 {@link #onStart()} 中完成).
     */
    protected void onInitialized() {
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onStop() {
    }

    /**
     * 关闭所有通过 {@link #manage} 注册的托管资源(LIFO),然后清空 context 引用.
     * <p>
     * 子类如需做额外清理,覆盖此方法时务必调用 {@code super.onDestroy()}.
     */
    @Override
    public void onDestroy() {
        for (int i = managedResources.size() - 1; i >= 0; i--) {
            try {
                managedResources.get(i).close();
            } catch (Exception ignored) {
            }
        }
        managedResources.clear();
        context = null;
    }

    // ---- 资源托管 ----

    /**
     * 注册托管资源,{@code onDestroy} 时自动关闭(LIFO 顺序).
     * <p>
     * 适合注册在整个插件生命周期都需要持有的资源(连接池,缓存等).
     * 若资源需要在 {@code onStop} 时释放(如后台调度器),请在 {@code onStop} 中手动调用 shutdown,
     * {@code manage} 会在 {@code onDestroy} 时再次调用 {@code close},由于幂等性不会重复释放.
     *
     * @param resource 需要托管的资源,{@code null} 时直接忽略
     * @return 传入的 resource,方便链式赋值:{@code scheduler = manage(ManagedScheduler.create(...))}
     */
    protected final <T extends AutoCloseable> T manage(T resource) {
        if (resource != null) {
            managedResources.add(resource);
        }
        return resource;
    }

    // ---- 上下文访问 ----

    /**
     * 返回插件运行时上下文,仅在 {@link #onInitialized()} 之后可用.
     */
    protected final PluginContext context() {
        return context;
    }

    /**
     * 返回当前插件的元数据描述符.
     */
    protected final PluginDescriptor descriptor() {
        return context.getDescriptor();
    }

    /**
     * 返回当前插件 ID(plugin.json 中的 {@code id} 字段).
     */
    protected final String pluginId() {
        return context.getDescriptor().id();
    }

    /**
     * 返回插件专属工作目录({@code pluginsDir/<pluginId>/}).
     */
    protected final Path workDir() {
        return context.getPluginWorkDir();
    }

    // ---- 配置 ----

    /**
     * 读取插件配置(来源:工作目录下的 {@code config.properties}),不存在返回 {@code null}.
     */
    protected final String property(String key) {
        return context.getProperty(key);
    }

    /**
     * 读取插件配置,不存在时返回 {@code defaultValue}.
     */
    protected final String property(String key, String defaultValue) {
        return context.getProperty(key, defaultValue);
    }

    // ---- 扩展 ----

    /**
     * 获取指定扩展点的所有实现,按 {@code @Extension.order} 升序排列.
     */
    protected final <T> List<T> extensions(Class<T> extensionPoint) {
        return context.getExtensions(extensionPoint);
    }

    /**
     * 获取优先级最高(order 最小)的单个扩展,没有实现时返回 {@link Optional#empty()}.
     * <p>
     * 适合 {@code allowMultiple = false} 的扩展点,或只关心最高优先级实现的场景.
     */
    protected final <T> Optional<T> firstExtension(Class<T> extensionPoint) {
        List<T> list = context.getExtensions(extensionPoint);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    // ---- 事件 ----

    /**
     * 发布事件至框架事件总线.
     */
    protected final void publish(PluginEvent event) {
        context.publishEvent(event);
    }

    /**
     * 订阅指定类型的事件,插件 UNLOADED 时由框架自动清理.
     */
    protected final <T extends PluginEvent> void subscribe(Class<T> eventType,
                                                           PluginEventListener<T> listener) {
        context.subscribe(eventType, listener);
    }

    // ---- 权限 ----

    /**
     * 判断当前插件是否声明了指定权限(plugin.json 的 {@code permissions} 字段).
     */
    protected final boolean hasPermission(String permission) {
        return context.getDescriptor().permissions().contains(permission);
    }

    /**
     * 断言当前插件已声明指定权限,未声明则抛出 {@link PluginPermissionException}.
     * <p>
     * 建议在 {@link #onInitialized()} 中调用,提前发现权限配置错误.
     */
    protected final void requirePermission(String permission) {
        if (!hasPermission(permission)) {
            throw new PluginPermissionException(
                    pluginId() + " 未声明所需权限: " + permission);
        }
    }

    // ---- 插件管理 ----

    /**
     * 获取插件管理器.
     */
    protected final PluginManager pluginManager() {
        return context.getPluginManager();
    }

    /**
     * 获取指定插件的当前状态,插件不存在时返回 {@code null}.
     */
    protected final PluginState getPluginState(String targetPluginId) {
        return context.getPluginManager().getPluginState(targetPluginId);
    }
}
