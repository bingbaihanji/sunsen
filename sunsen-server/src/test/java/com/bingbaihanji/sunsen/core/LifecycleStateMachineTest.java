package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.IllegalStateTransitionException;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginState;
import com.bingbaihanji.sunsen.api.event.builtin.PluginFailedEvent;
import com.bingbaihanji.sunsen.core.lifecycle.LifecycleStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleStateMachineTest {

    LifecycleStateMachine machine;
    List<PluginFailedEvent> capturedFailedEvents;

    @BeforeEach
    void setUp() {
        capturedFailedEvents = new ArrayList<>();
        PluginEventBus bus = new PluginEventBus();
        bus.subscribe(PluginFailedEvent.class, capturedFailedEvents::add);
        machine = new LifecycleStateMachine(bus);
    }

    private PluginDescriptor descriptor(String id) {
        return new PluginDescriptor(id, id, "", "1.0.0", "1.0", "Main",
                List.of("com.test." + id), List.of(), Set.of(), Map.of());
    }

    @Test
    void testNormalLifecycle() {
        machine.init("p1", PluginState.CREATED);
        assertEquals(PluginState.CREATED, machine.getState("p1"));

        machine.transition("p1", PluginState.CREATED, PluginState.RESOLVED);
        machine.transition("p1", PluginState.RESOLVED, PluginState.LOADED);
        machine.transition("p1", PluginState.LOADED, PluginState.STARTING);
        machine.transition("p1", PluginState.STARTING, PluginState.ACTIVE);
        machine.transition("p1", PluginState.ACTIVE, PluginState.STOPPING);
        machine.transition("p1", PluginState.STOPPING, PluginState.STOPPED);
        machine.transition("p1", PluginState.STOPPED, PluginState.UNLOADED);
        assertEquals(PluginState.UNLOADED, machine.getState("p1"));
    }

    @Test
    void testIllegalTransitionThrows() {
        machine.init("p1", PluginState.CREATED);
        assertThrows(IllegalStateTransitionException.class,
                () -> machine.transition("p1", PluginState.CREATED, PluginState.ACTIVE));
    }

    @Test
    void testCasMismatchThrows() {
        machine.init("p1", PluginState.LOADED);
        // 提供的 from 与实际状态不符
        assertThrows(IllegalStateTransitionException.class,
                () -> machine.transition("p1", PluginState.CREATED, PluginState.RESOLVED));
    }

    @Test
    void testUnregisteredPluginThrows() {
        assertThrows(IllegalStateTransitionException.class,
                () -> machine.transition("ghost", PluginState.CREATED, PluginState.RESOLVED));
    }

    @Test
    void testGetStateReturnsNullForUnknown() {
        assertNull(machine.getState("nonexistent"));
    }

    @Test
    void testForceSet() {
        machine.init("p1", PluginState.STARTING);
        machine.forceSet("p1", PluginState.FAILED);
        assertEquals(PluginState.FAILED, machine.getState("p1"));
    }

    @Test
    void testForceSetNoopForUnknownPlugin() {
        assertDoesNotThrow(() -> machine.forceSet("ghost", PluginState.FAILED));
    }

    @Test
    void testRemove() {
        machine.init("p1", PluginState.CREATED);
        machine.remove("p1");
        assertNull(machine.getState("p1"));
    }

    @Test
    void testExecutePhaseSuccess() {
        machine.init("p1", PluginState.CREATED);
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        machine.executePhase(() -> ran.set(true), descriptor("p1"), PluginState.CREATED);
        assertTrue(ran.get());
        assertEquals(PluginState.CREATED, machine.getState("p1"));
        assertTrue(capturedFailedEvents.isEmpty());
    }

    @Test
    void testExecutePhaseFailureTransitionsToFailedAndPublishesEvent() {
        machine.init("p1", PluginState.LOADED);
        RuntimeException cause = new RuntimeException("boom");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> machine.executePhase(() -> { throw cause; }, descriptor("p1"), PluginState.LOADED));
        assertSame(cause, thrown);
        assertEquals(PluginState.FAILED, machine.getState("p1"));
        assertEquals(1, capturedFailedEvents.size());
        assertSame(cause, capturedFailedEvents.get(0).getCause());
        assertEquals(PluginState.LOADED, capturedFailedEvents.get(0).getStateAtFailure());
    }

    @Test
    void testRestartFromStopped() {
        machine.init("p1", PluginState.CREATED);
        machine.transition("p1", PluginState.CREATED, PluginState.RESOLVED);
        machine.transition("p1", PluginState.RESOLVED, PluginState.LOADED);
        machine.transition("p1", PluginState.LOADED, PluginState.STARTING);
        machine.transition("p1", PluginState.STARTING, PluginState.ACTIVE);
        machine.transition("p1", PluginState.ACTIVE, PluginState.STOPPING);
        machine.transition("p1", PluginState.STOPPING, PluginState.STOPPED);
        // STOPPED 可以重新进入 STARTING
        machine.transition("p1", PluginState.STOPPED, PluginState.STARTING);
        assertEquals(PluginState.STARTING, machine.getState("p1"));
    }

    @Test
    void testFailedCanOnlyTransitionToUnloaded() {
        machine.init("p1", PluginState.FAILED);
        // FAILED → UNLOADED 合法
        machine.transition("p1", PluginState.FAILED, PluginState.UNLOADED);
        assertEquals(PluginState.UNLOADED, machine.getState("p1"));
    }

    @Test
    void testFailedCannotTransitionToActive() {
        machine.init("p1", PluginState.FAILED);
        assertThrows(IllegalStateTransitionException.class,
                () -> machine.transition("p1", PluginState.FAILED, PluginState.ACTIVE));
    }

    @Test
    void testMultiplePluginsIsolated() {
        machine.init("p1", PluginState.CREATED);
        machine.init("p2", PluginState.LOADED);

        machine.transition("p1", PluginState.CREATED, PluginState.RESOLVED);
        assertEquals(PluginState.RESOLVED, machine.getState("p1"));
        // p2 不受 p1 状态变化影响
        assertEquals(PluginState.LOADED, machine.getState("p2"));
    }
}
