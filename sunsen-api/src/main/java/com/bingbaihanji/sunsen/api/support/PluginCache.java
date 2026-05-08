package com.bingbaihanji.sunsen.api.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 插件生命周期内的内存缓存,键值对存储,插件卸载时自动释放.
 * <p>
 * 实现 {@link AutoCloseable},推荐配合 {@link AbstractPlugin#manage(AutoCloseable)} 使用,
 * 在 {@code onDestroy} 时自动清空所有条目,避免已卸载插件的对象长期滞留堆内存.
 * <p>
 * 内部使用 {@link ConcurrentHashMap},支持并发访问.
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * public class MyPlugin extends AbstractPlugin {
 *     private PluginCache<String, UserProfile> userCache;
 *
 *     @Override
 *     protected void onInitialized() {
 *         userCache = manage(new PluginCache<>("user"));
 *     }
 *
 *     private UserProfile getUser(String userId) {
 *         return userCache.computeIfAbsent(userId, id -> fetchFromDatabase(id));
 *     }
 * }
 * }</pre>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public final class PluginCache<K, V> implements AutoCloseable {

    private final String name;
    private final Map<K, V> store = new ConcurrentHashMap<>();

    /**
     * @param name 缓存名称,用于日志和调试识别
     */
    public PluginCache(String name) {
        this.name = name;
    }

    /**
     * 根据键获取值,不存在返回 {@code null}.
     */
    public V get(K key) {
        return store.get(key);
    }

    /**
     * 存入键值对.
     *
     * @return 之前的值,不存在返回 {@code null}
     */
    public V put(K key, V value) {
        return store.put(key, value);
    }

    /**
     * 如果不存在则计算并存入,返回当前(已有或新计算)值.
     *
     * @param key             键
     * @param mappingFunction 值计算函数
     * @return 当前值
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        return store.computeIfAbsent(key, mappingFunction);
    }

    /**
     * 如果存在则移除并返回旧值.
     *
     * @return 被移除的值,不存在返回 {@code null}
     */
    public V remove(K key) {
        return store.remove(key);
    }

    /**
     * 判断是否包含指定键.
     */
    public boolean containsKey(K key) {
        return store.containsKey(key);
    }

    /**
     * 当前缓存条目数.
     */
    public int size() {
        return store.size();
    }

    /**
     * 是否为空.
     */
    public boolean isEmpty() {
        return store.isEmpty();
    }

    /**
     * 清空所有条目.
     */
    public void clear() {
        store.clear();
    }

    /**
     * 缓存名称.
     */
    public String name() {
        return name;
    }

    @Override
    public void close() {
        store.clear();
    }
}
