package io.papermc.paper.agc.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class AgcLockFreeEventDispatcherTest {

    @BeforeEach
    public void setup() {
        AgcLockFreeEventDispatcher.get().clear();
    }

    public static class TestEvent {
        public final String payload;

        public TestEvent(final String payload) {
            this.payload = payload;
        }
    }

    public static class ChildEvent extends TestEvent {
        public ChildEvent(final String payload) {
            super(payload);
        }
    }

    public static class CancellableTestEvent extends TestEvent implements AgcCancellable {
        private boolean cancelled;

        public CancellableTestEvent(final String payload) {
            super(payload);
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(final boolean cancel) {
            this.cancelled = cancel;
        }
    }

    @Test
    public void testLockFreeEventDispatchAndUnregister() {
        final AgcLockFreeEventDispatcher dispatcher = AgcLockFreeEventDispatcher.get();

        final List<String> received1 = new ArrayList<>();
        final List<String> received2 = new ArrayList<>();

        final Consumer<TestEvent> listener1 = e -> received1.add(e.payload);
        final Consumer<TestEvent> listener2 = e -> received2.add(e.payload);

        dispatcher.registerListener(TestEvent.class, listener1);
        dispatcher.registerListener(TestEvent.class, listener2);

        assertEquals(1, dispatcher.metrics().registeredEventTypes());

        final int count1 = dispatcher.fireEvent(new TestEvent("Hello AGC"));
        assertEquals(2, count1);
        assertEquals(1, received1.size());
        assertEquals("Hello AGC", received1.get(0));
        assertEquals(1, received2.size());
        assertEquals("Hello AGC", received2.get(0));

        // Unregister listener 1
        dispatcher.unregisterListener(TestEvent.class, listener1);
        final int count2 = dispatcher.fireEvent(new TestEvent("Second Event"));
        assertEquals(1, count2);
        assertEquals(1, received1.size()); // Unchanged
        assertEquals(2, received2.size());
        assertEquals("Second Event", received2.get(1));
    }

    @Test
    public void testPriorityOrdering() {
        final AgcLockFreeEventDispatcher dispatcher = AgcLockFreeEventDispatcher.get();
        final List<String> callOrder = new ArrayList<>();

        dispatcher.registerListener(TestEvent.class, AgcLockFreeEventDispatcher.Priority.MONITOR, e -> callOrder.add("MONITOR"), false);
        dispatcher.registerListener(TestEvent.class, AgcLockFreeEventDispatcher.Priority.LOWEST, e -> callOrder.add("LOWEST"), false);
        dispatcher.registerListener(TestEvent.class, AgcLockFreeEventDispatcher.Priority.HIGH, e -> callOrder.add("HIGH"), false);
        dispatcher.registerListener(TestEvent.class, AgcLockFreeEventDispatcher.Priority.NORMAL, e -> callOrder.add("NORMAL"), false);

        dispatcher.fireEvent(new TestEvent("order"));

        assertEquals(List.of("LOWEST", "NORMAL", "HIGH", "MONITOR"), callOrder);
    }

    @Test
    public void testHierarchyDispatch() {
        final AgcLockFreeEventDispatcher dispatcher = AgcLockFreeEventDispatcher.get();
        final List<String> parentList = new ArrayList<>();
        final List<String> childList = new ArrayList<>();

        dispatcher.registerListener(TestEvent.class, e -> parentList.add(e.payload));
        dispatcher.registerListener(ChildEvent.class, e -> childList.add(e.payload));

        // Firing ChildEvent should invoke ChildEvent listener AND parent TestEvent listener
        dispatcher.fireEvent(new ChildEvent("child"));

        assertEquals(1, childList.size());
        assertEquals(1, parentList.size());
    }

    @Test
    public void testCancellableBailout() {
        final AgcLockFreeEventDispatcher dispatcher = AgcLockFreeEventDispatcher.get();
        final List<String> executed = new ArrayList<>();

        // Canceller at LOWEST
        dispatcher.registerListener(CancellableTestEvent.class, AgcLockFreeEventDispatcher.Priority.LOWEST, e -> {
            executed.add("LOWEST_CANCELLER");
            e.setCancelled(true);
        }, false);

        // NORMAL does not ignore cancelled -> should be skipped!
        dispatcher.registerListener(CancellableTestEvent.class, AgcLockFreeEventDispatcher.Priority.NORMAL, e -> {
            executed.add("NORMAL_SKIPPED");
        }, false);

        // MONITOR ignores cancelled -> should execute!
        dispatcher.registerListener(CancellableTestEvent.class, AgcLockFreeEventDispatcher.Priority.MONITOR, e -> {
            executed.add("MONITOR_EXECUTED");
        }, true);

        dispatcher.fireEvent(new CancellableTestEvent("test"));

        assertEquals(List.of("LOWEST_CANCELLER", "MONITOR_EXECUTED"), executed);
        assertEquals(1, dispatcher.metrics().cancelledListenersSkipped());
    }

    public static class BukkitCancellableTestEvent implements org.bukkit.event.Cancellable {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(final boolean cancel) {
            this.cancelled = cancel;
        }
    }

    @Test
    public void testCrossHierarchyPriorityOrdering() {
        final AgcLockFreeEventDispatcher dispatcher = AgcLockFreeEventDispatcher.get();
        final List<String> order = new ArrayList<>();

        // Child event listener registered at MONITOR
        dispatcher.registerListener(ChildEvent.class, AgcLockFreeEventDispatcher.Priority.MONITOR, e -> order.add("CHILD_MONITOR"), false);
        // Parent event listener registered at LOWEST
        dispatcher.registerListener(TestEvent.class, AgcLockFreeEventDispatcher.Priority.LOWEST, e -> order.add("PARENT_LOWEST"), false);

        dispatcher.fireEvent(new ChildEvent("hierarchy-test"));

        // LOWEST must execute BEFORE MONITOR regardless of inheritance depth!
        assertEquals(List.of("PARENT_LOWEST", "CHILD_MONITOR"), order);
    }

    @Test
    public void testBukkitCancellableSupport() {
        final AgcLockFreeEventDispatcher dispatcher = AgcLockFreeEventDispatcher.get();
        final List<String> callLog = new ArrayList<>();

        dispatcher.registerListener(BukkitCancellableTestEvent.class, AgcLockFreeEventDispatcher.Priority.LOW, e -> {
            callLog.add("LOW_CANCEL");
            e.setCancelled(true);
        }, false);

        dispatcher.registerListener(BukkitCancellableTestEvent.class, AgcLockFreeEventDispatcher.Priority.NORMAL, e -> {
            callLog.add("NORMAL_SHOULD_BE_SKIPPED");
        }, false);

        dispatcher.registerListener(BukkitCancellableTestEvent.class, AgcLockFreeEventDispatcher.Priority.MONITOR, e -> {
            callLog.add("MONITOR_CALLED");
        }, true);

        final BukkitCancellableTestEvent evt = new BukkitCancellableTestEvent();
        dispatcher.fireEvent(evt);

        assertEquals(List.of("LOW_CANCEL", "MONITOR_CALLED"), callLog);
        assertTrue(evt.isCancelled());
    }
}
