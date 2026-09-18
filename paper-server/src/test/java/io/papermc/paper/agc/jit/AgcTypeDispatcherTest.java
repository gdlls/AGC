package io.papermc.paper.agc.jit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcTypeDispatcherTest {

    @BeforeEach
    public void setup() {
        AgcTypeDispatcher.get().clear();
    }

    @Test
    public void testMonomorphicFastPathDispatch() {
        final AgcTypeDispatcher dispatcher = AgcTypeDispatcher.get();
        final AtomicBoolean invoked = new AtomicBoolean(false);

        dispatcher.dispatch(AgcTypeDispatcher.TypeId.ZOMBIE, "ZombieTarget", target -> invoked.set(true));

        assertTrue(invoked.get());
        assertEquals(1, dispatcher.metrics().fastPathDispatches());
        assertEquals(0, dispatcher.metrics().megamorphicFallbacks());
    }

    @Test
    public void testBatchedExecutionByType() {
        final AgcTypeDispatcher dispatcher = AgcTypeDispatcher.get();

        record DummyEntity(String name, AgcTypeDispatcher.TypeId type) {}

        final List<DummyEntity> entities = List.of(
            new DummyEntity("z1", AgcTypeDispatcher.TypeId.ZOMBIE),
            new DummyEntity("z2", AgcTypeDispatcher.TypeId.ZOMBIE),
            new DummyEntity("s1", AgcTypeDispatcher.TypeId.SKELETON),
            new DummyEntity("p1", AgcTypeDispatcher.TypeId.PLAYER)
        );

        final List<List<DummyEntity>> executedBatches = new ArrayList<>();
        dispatcher.executeBatchedByTypes(entities, DummyEntity::type, executedBatches::add);

        assertEquals(3, executedBatches.size());
        assertEquals(3, dispatcher.metrics().loopBatchesExecuted());
    }

    @Test
    public void testPrimitiveNonBoxingDispatch() {
        final AgcTypeDispatcher dispatcher = AgcTypeDispatcher.get();
        final AtomicInteger intVal = new AtomicInteger(0);

        dispatcher.dispatchInt(AgcTypeDispatcher.TypeId.CREEPER, 42, intVal::set);
        assertEquals(42, intVal.get());

        assertEquals(1, dispatcher.metrics().primitiveDispatches());
        assertEquals(AgcTypeDispatcher.EntityCategory.MONSTER, AgcTypeDispatcher.TypeId.CREEPER.category());
    }
}
