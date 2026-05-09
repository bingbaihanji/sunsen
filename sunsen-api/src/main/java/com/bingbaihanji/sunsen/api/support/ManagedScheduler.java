package com.bingbaihanji.sunsen.api.support;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生命周期感知的定时任务调度器,封装 {@link ScheduledExecutorService}.
 * <p>
 * 实现 {@link AutoCloseable},可配合 {@link AbstractPlugin#manage(AutoCloseable)} 使用:
 *
 * <pre>{@code
 * @Override
 * public void onStart() {
 *     ManagedScheduler scheduler = manage(ManagedScheduler.create(pluginId()));
 *     scheduler.scheduleWithFixedDelay(this::poll, 0, 30, TimeUnit.SECONDS);
 * }
 * // onStop / onDestroy 无需额外代码,close() 会在 onDestroy 自动调用
 * }</pre>
 *
 * <p>若需要在 {@code onStop} 时优雅停止(等待正在执行的任务完成),在 {@code onStop} 中显式调用
 * {@link #shutdown()};{@code onDestroy} 时再次调用 {@link #close()} 是幂等的,不会出错.
 *
 * <h3>线程命名</h3>
 * 单线程模式线程名为 {@code plugin-scheduler-<pluginId>};
 * 多线程模式为 {@code plugin-scheduler-<pluginId>-1},{@code plugin-scheduler-<pluginId>-2} ......
 * 便于线程转储(thread dump)中定位问题来源.
 */
public final class ManagedScheduler implements AutoCloseable {

    private final ScheduledExecutorService executor;

    private ManagedScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    /**
     * 创建单线程调度器.
     *
     * @param pluginId 所属插件 ID,用于线程命名
     */
    public static ManagedScheduler create(String pluginId) {
        return create(pluginId, 1);
    }

    /**
     * 创建多线程调度器.
     *
     * @param pluginId 所属插件 ID,用于线程命名
     * @param poolSize 线程数,通常为 1;有多条独立流水线时可适当增大
     * @throws IllegalArgumentException poolSize &lt; 1
     */
    public static ManagedScheduler create(String pluginId, int poolSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1, got: " + poolSize);
        }
        AtomicInteger counter = new AtomicInteger(0);
        return create(pluginId, poolSize, r -> {
            String name = poolSize == 1
                    ? "plugin-scheduler-" + pluginId
                    : "plugin-scheduler-" + pluginId + "-" + counter.incrementAndGet();
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 使用指定 {@link ThreadFactory} 创建调度器.
     * <p>
     * 配合 {@link com.bingbaihanji.sunsen.api.support.AbstractPlugin#threadFactory()} 使用,
     * 确保调度器线程归属于插件专属 ThreadGroup,插件卸载时会被统一中断:
     * <pre>{@code
     * ManagedScheduler scheduler = manage(
     *     ManagedScheduler.create(pluginId(), 1, threadFactory())
     * );
     * }</pre>
     *
     * @param pluginId 所属插件 ID(仅用于日志/异常信息)
     * @param poolSize 线程数
     * @param factory  自定义线程工厂
     * @throws IllegalArgumentException poolSize &lt; 1
     */
    public static ManagedScheduler create(String pluginId, int poolSize, ThreadFactory factory) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1, got: " + poolSize);
        }
        return new ManagedScheduler(Executors.newScheduledThreadPool(poolSize, factory));
    }

    // ---- 任务调度 ----

    /**
     * 以固定速率周期执行(不等待上次任务结束).
     *
     * @see ScheduledExecutorService#scheduleAtFixedRate
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task,
                                                  long initialDelay,
                                                  long period,
                                                  TimeUnit unit) {
        return executor.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    /**
     * 上次任务结束后再延迟指定时间执行(适合耗时不定的任务,防止重叠).
     *
     * @see ScheduledExecutorService#scheduleWithFixedDelay
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,
                                                     long initialDelay,
                                                     long delay,
                                                     TimeUnit unit) {
        return executor.scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    /**
     * 延迟执行一次性任务.
     */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return executor.schedule(task, delay, unit);
    }

    /**
     * 立即提交一次性任务.
     */
    public void submit(Runnable task) {
        executor.submit(task);
    }

    // ---- 停止 ----

    /**
     * 优雅停止:不再接受新任务,等待最多 5 秒让正在执行的任务完成;超时后强制中断.
     * <p>
     * 幂等:对已停止的调度器重复调用无任何副作用.
     * 建议在插件 {@code onStop()} 中调用.
     */
    public void shutdown() {
        if (executor.isShutdown()) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 立即终止,不等待正在执行的任务完成.用于紧急场景,优先使用 {@link #shutdown()}.
     */
    public void shutdownNow() {
        executor.shutdownNow();
    }

    /**
     * 实现 {@link AutoCloseable},委托给 {@link #shutdown()}.
     * 配合 {@link AbstractPlugin#manage(AutoCloseable)} 使用,在插件 onDestroy 时自动调用.
     */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * 调度器是否已停止(包含 shutdown 触发后尚未完全终止的状态).
     */
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * 所有任务是否已完全终止.
     */
    public boolean isTerminated() {
        return executor.isTerminated();
    }
}
