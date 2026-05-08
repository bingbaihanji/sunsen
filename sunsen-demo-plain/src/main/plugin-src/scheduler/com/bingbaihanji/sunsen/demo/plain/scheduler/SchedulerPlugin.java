package com.bingbaihanji.sunsen.demo.plain.scheduler;

import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.api.annotation.Plugin;
import com.bingbaihanji.sunsen.api.support.AbstractPlugin;
import com.bingbaihanji.sunsen.api.support.ManagedScheduler;
import com.bingbaihanji.sunsen.demo.plain.GreetingFormatter;
import com.bingbaihanji.sunsen.demo.plain.event.GreetingEvent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduler 演示插件
 * <p>
 * 高级用法演示：
 * <ul>
 *   <li>{@link ManagedScheduler} 后台定时任务，线程命名 {@code plugin-scheduler-<pluginId>}</li>
 *   <li>{@code manage(scheduler)} 注册到 LIFO 资源链，{@code onDestroy} 自动 close</li>
 *   <li>{@code onStop} 中显式 {@code shutdown}：插件 STOPPED 状态期间停止任务，UNLOADED 时
 *       {@code close()}（幂等）再次清理</li>
 *   <li>定期 {@code publish(GreetingEvent)} 触发 HelloPlugin 的订阅者</li>
 *   <li>贡献单一 {@link GreetingFormatter} 实现（{@code allowMultiple=false}）</li>
 * </ul>
 */
@Plugin(
        id = "com.bingbaihanji.sunsen.demo.plain.scheduler",
        name = "Scheduler Plugin",
        version = "1.0.0",
        packagePrefixes = "com.bingbaihanji.sunsen.demo.plain.scheduler",
        permissions = "event:publish"
)
public class SchedulerPlugin extends AbstractPlugin {

    private ManagedScheduler scheduler;
    private final AtomicInteger tickCount = new AtomicInteger(0);

    @Override
    protected void onInitialized() {
        requirePermission("event:publish");
        System.out.println("[SchedulerPlugin] onInitialized");
    }

    @Override
    public void onStart() {
        // manage() 把调度器注册到 onDestroy 的清理列表，并直接返回 scheduler 用于赋值
        scheduler = manage(ManagedScheduler.create(pluginId()));
        scheduler.scheduleWithFixedDelay(this::tick, 500, 1000, TimeUnit.MILLISECONDS);
        System.out.println("[SchedulerPlugin] onStart | 已启动定时器 (1s)");
    }

    private void tick() {
        int n = tickCount.incrementAndGet();
        // publish 会校验 sourcePluginId 必须等于当前插件 ID
        publish(new GreetingEvent(pluginId(), "tick #" + n));
    }

    @Override
    public void onStop() {
        // 优雅停止：onStop 阶段不再发布事件，避免在停止过程中干扰其他插件
        // close() 在 onDestroy 时会再次调用，shutdown 是幂等的
        if (scheduler != null) {
            scheduler.shutdown();
        }
        System.out.println("[SchedulerPlugin] onStop | 共触发 " + tickCount.get() + " 次 tick");
    }
}

@Extension(order = 1, description = "★ 星号包裹格式化器")
class StarFormatter implements GreetingFormatter {
    @Override
    public String format(String greeting) {
        return "★ " + greeting + " ★";
    }
}
