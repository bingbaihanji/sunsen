package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.PluginEventListener;
import com.bingbaihanji.sunsen.api.event.builtin.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PluginEventBusTest {

    @Test
    void exactTypeMatching() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();
        bus.subscribe(PluginLoadedEvent.class, e -> counter.incrementAndGet());

        bus.publish(new PluginLoadedEvent(descriptor("test", "1.0")));
        assertEquals(1, counter.get());
    }

    @Test
    void noMatchOnDifferentType() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();
        bus.subscribe(PluginLoadedEvent.class, e -> counter.incrementAndGet());

        bus.publish(new PluginStartedEvent(descriptor("test", "1.0")));
        assertEquals(0, counter.get());
    }

    @Test
    void parentTypeSubscriptionReceivesAll() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();
        bus.subscribe(PluginEvent.class, e -> counter.incrementAndGet());

        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginStartedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginStoppedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginUnloadedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginFailedEvent(descriptor("a", "1.0"), null, new Exception()));

        assertEquals(5, counter.get());
    }

    @Test
    void unsubscribeStopsDelivery() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();
        PluginEventListener<PluginLoadedEvent> listener = e -> counter.incrementAndGet();
        bus.subscribe(PluginLoadedEvent.class, listener);

        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));
        bus.unsubscribe(PluginLoadedEvent.class, listener);
        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));

        assertEquals(1, counter.get());
    }

    @Test
    void unsubscribeAllByPluginId() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();

        bus.subscribe(PluginLoadedEvent.class, e -> counter.incrementAndGet(), "plugin-A");
        bus.subscribe(PluginStartedEvent.class, e -> counter.addAndGet(10), "plugin-A");
        bus.subscribe(PluginStoppedEvent.class, e -> counter.addAndGet(100), "plugin-B");

        bus.unsubscribeAll("plugin-A");

        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginStartedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginStoppedEvent(descriptor("a", "1.0")));

        assertEquals(100, counter.get());
    }

    @Test
    void listenerExceptionDoesNotAffectOthers() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();

        bus.subscribe(PluginLoadedEvent.class, e -> { throw new RuntimeException("boom"); });
        bus.subscribe(PluginLoadedEvent.class, e -> counter.incrementAndGet());

        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));
        assertEquals(1, counter.get());
    }

    @Test
    void syncDispatchRunsOnCallerThread() {
        PluginEventBus bus = new PluginEventBus();
        Thread callerThread = Thread.currentThread();
        List<Thread> dispatchThreads = new CopyOnWriteArrayList<>();

        bus.subscribe(PluginLoadedEvent.class, e -> dispatchThreads.add(Thread.currentThread()));
        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));

        assertEquals(1, dispatchThreads.size());
        assertSame(callerThread, dispatchThreads.get(0));
    }

    private static PluginDescriptor descriptor(String id, String version) {
        return new PluginDescriptor(id, "Test", "", version, "1.0", "com.test.Main",
                List.of("com.test"), List.of(), java.util.Set.of(), java.util.Map.of());
    }
}
