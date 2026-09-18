package io.papermc.paper.agc.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * AGC — High-Performance Lock-Free Hierarchical Event Dispatcher.
 *
 * <p>Replaces synchronized HandlerList collections with Copy-On-Write contiguous listener arrays.
 * Supports priority order (LOWEST..MONITOR), cancellable event fast-path bailing, and
 * cached class hierarchy resolution for superclass/interface listeners with zero lock contention.</p>
 */
public final class AgcLockFreeEventDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcLockFreeEventDispatcher.class);
    private static final AgcLockFreeEventDispatcher INSTANCE = new AgcLockFreeEventDispatcher();

    public enum Priority {
        LOWEST(0),
        LOW(1),
        NORMAL(2),
        HIGH(3),
        HIGHEST(4),
        MONITOR(5);

        private final int slot;

        Priority(final int slot) {
            this.slot = slot;
        }

        public int slot() {
            return this.slot;
        }
    }

    public static final class RegisteredListener<E> {
        private final Priority priority;
        private final Consumer<E> listener;
        private final boolean ignoreCancelled;

        public RegisteredListener(final Priority priority, final Consumer<E> listener, final boolean ignoreCancelled) {
            this.priority = priority;
            this.listener = listener;
            this.ignoreCancelled = ignoreCancelled;
        }

        public Priority priority() { return this.priority; }
        public Consumer<E> listener() { return this.listener; }
        public boolean ignoreCancelled() { return this.ignoreCancelled; }
    }

    @SuppressWarnings("rawtypes")
    private final ConcurrentHashMap<Class<?>, RegisteredListener[]> listenerMap = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private final ConcurrentHashMap<Class<?>, RegisteredListener[]> effectiveListenersCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Class<?>, Class<?>[]> hierarchyCache = new ConcurrentHashMap<>();

    private static final RegisteredListener<?>[] EMPTY_LISTENERS = new RegisteredListener<?>[0];

    private final AtomicLong eventsDispatched = new AtomicLong();
    private final AtomicLong listenersInvoked = new AtomicLong();
    private final AtomicLong emptyDispatchesSkipped = new AtomicLong();
    private final AtomicLong cancelledListenersSkipped = new AtomicLong();

    public static AgcLockFreeEventDispatcher get() {
        return INSTANCE;
    }

    private AgcLockFreeEventDispatcher() {}

    /**
     * Registers an event listener with NORMAL priority and ignoreCancelled=false.
     */
    public synchronized <E> void registerListener(final Class<E> eventClass, final Consumer<E> listener) {
        registerListener(eventClass, Priority.NORMAL, listener, false);
    }

    /**
     * Registers an event listener with explicit priority and ignoreCancelled setting.
     */
    @SuppressWarnings("unchecked")
    public synchronized <E> void registerListener(
        final Class<E> eventClass,
        final Priority priority,
        final Consumer<E> listener,
        final boolean ignoreCancelled
    ) {
        if (eventClass == null || listener == null) return;

        final RegisteredListener<E> reg = new RegisteredListener<>(priority != null ? priority : Priority.NORMAL, listener, ignoreCancelled);
        final RegisteredListener[] current = this.listenerMap.get(eventClass);

        final RegisteredListener[] updated;
        if (current == null || current.length == 0) {
            updated = new RegisteredListener[]{reg};
        } else {
            updated = Arrays.copyOf(current, current.length + 1);
            updated[current.length] = reg;
            Arrays.sort(updated, Comparator.comparingInt(r -> r.priority().slot()));
        }
        this.listenerMap.put(eventClass, updated);
        this.hierarchyCache.clear();
        this.effectiveListenersCache.clear();
    }

    /**
     * Unregisters an event listener.
     */
    @SuppressWarnings("unchecked")
    public synchronized <E> void unregisterListener(final Class<E> eventClass, final Consumer<E> listener) {
        if (eventClass == null || listener == null) return;

        final RegisteredListener[] current = this.listenerMap.get(eventClass);
        if (current == null || current.length == 0) return;

        int index = -1;
        for (int i = 0; i < current.length; i++) {
            if (current[i].listener().equals(listener)) {
                index = i;
                break;
            }
        }

        if (index >= 0) {
            if (current.length == 1) {
                this.listenerMap.remove(eventClass);
            } else {
                final RegisteredListener[] updated = new RegisteredListener[current.length - 1];
                System.arraycopy(current, 0, updated, 0, index);
                System.arraycopy(current, index + 1, updated, index, current.length - index - 1);
                this.listenerMap.put(eventClass, updated);
            }
            this.hierarchyCache.clear();
            this.effectiveListenersCache.clear();
        }
    }

    private Class<?>[] resolveHierarchy(final Class<?> eventClass) {
        return this.hierarchyCache.computeIfAbsent(eventClass, clazz -> {
            final List<Class<?>> hierarchy = new ArrayList<>();
            Class<?> c = clazz;
            while (c != null && c != Object.class) {
                hierarchy.add(c);
                for (final Class<?> iface : c.getInterfaces()) {
                    if (!hierarchy.contains(iface)) {
                        hierarchy.add(iface);
                    }
                }
                c = c.getSuperclass();
            }
            return hierarchy.toArray(new Class<?>[0]);
        });
    }

    @SuppressWarnings("rawtypes")
    private RegisteredListener[] getEffectiveListeners(final Class<?> eventClass) {
        return this.effectiveListenersCache.computeIfAbsent(eventClass, clazz -> {
            final Class<?>[] hierarchy = resolveHierarchy(clazz);
            final List<RegisteredListener> list = new ArrayList<>();
            for (final Class<?> type : hierarchy) {
                final RegisteredListener[] registered = this.listenerMap.get(type);
                if (registered != null) {
                    list.addAll(Arrays.asList(registered));
                }
            }
            if (list.isEmpty()) {
                return EMPTY_LISTENERS;
            }
            list.sort(Comparator.comparingInt(r -> r.priority().slot()));
            return list.toArray(new RegisteredListener[0]);
        });
    }

    /**
     * Dispatches an event without locking on the hot-path.
     * Supports cancellation checks and class hierarchy traversal.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <E> int fireEvent(final E event) {
        if (event == null) return 0;
        this.eventsDispatched.incrementAndGet();

        final RegisteredListener[] listeners = getEffectiveListeners(event.getClass());
        if (listeners == null || listeners.length == 0) {
            this.emptyDispatchesSkipped.incrementAndGet();
            return 0;
        }

        int invoked = 0;
        for (final RegisteredListener reg : listeners) {
            if (isEventCancelled(event) && !reg.ignoreCancelled()) {
                this.cancelledListenersSkipped.incrementAndGet();
                continue;
            }
            try {
                reg.listener().accept(event);
                invoked++;
            } catch (final Throwable t) {
                LOGGER.error("[AGC Event] Error dispatching event {} to listener {}: {}",
                    event.getClass().getSimpleName(), reg.listener().getClass().getName(), t.getMessage(), t);
            }
        }

        if (invoked == 0) {
            this.emptyDispatchesSkipped.incrementAndGet();
        } else {
            this.listenersInvoked.addAndGet(invoked);
        }

        return invoked;
    }

    private boolean isEventCancelled(final Object event) {
        if (event instanceof org.bukkit.event.Cancellable bukkitCancellable) {
            return bukkitCancellable.isCancelled();
        }
        if (event instanceof io.papermc.paper.agc.event.AgcCancellable cancellable) {
            return cancellable.isCancelled();
        }
        return false;
    }

    public void clear() {
        this.listenerMap.clear();
        this.hierarchyCache.clear();
        this.eventsDispatched.set(0);
        this.listenersInvoked.set(0);
        this.emptyDispatchesSkipped.set(0);
        this.cancelledListenersSkipped.set(0);
    }

    public EventDispatcherMetrics metrics() {
        return new EventDispatcherMetrics(
            this.listenerMap.size(),
            this.eventsDispatched.get(),
            this.listenersInvoked.get(),
            this.emptyDispatchesSkipped.get(),
            this.cancelledListenersSkipped.get()
        );
    }

    public record EventDispatcherMetrics(
        int registeredEventTypes,
        long eventsDispatched,
        long listenersInvoked,
        long emptyDispatchesSkipped,
        long cancelledListenersSkipped
    ) {}
}
