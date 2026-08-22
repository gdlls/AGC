package io.papermc.paper.agc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcHotObjectPool}.
 */
class AgcHotObjectPoolTest {

    private static final class TestVector {
        double x, y, z;
        void reset() {
            this.x = 0;
            this.y = 0;
            this.z = 0;
        }
    }

    @Test
    void acquireAndReleaseReusesObject() {
        final AtomicInteger creationCount = new AtomicInteger(0);
        final AgcHotObjectPool<TestVector> pool = new AgcHotObjectPool<>(
            () -> {
                creationCount.incrementAndGet();
                return new TestVector();
            },
            TestVector::reset,
            16
        );

        final TestVector v1 = pool.acquire();
        assertEquals(1, creationCount.get());
        v1.x = 10;
        v1.y = 20;

        pool.release(v1);
        assertEquals(1, pool.currentThreadLocalPoolSize());

        // Second acquire should reuse v1 and reset its fields
        final TestVector v2 = pool.acquire();
        assertSame(v1, v2);
        assertEquals(0, v2.x);
        assertEquals(0, v2.y);
        assertEquals(1, creationCount.get()); // No new allocation!
        assertEquals(0, pool.currentThreadLocalPoolSize());

        final var m = pool.metrics();
        assertEquals(2, m.acquires());
        assertEquals(1, m.releases());
        assertEquals(1, m.creations());
        assertEquals(0.5, m.reuseRatio(), 0.001);
    }

    @Test
    void poolCapacityLimitIsRespected() {
        final AgcHotObjectPool<TestVector> pool = new AgcHotObjectPool<>(
            TestVector::new,
            TestVector::reset,
            2 // max capacity = 2
        );

        final TestVector v1 = pool.acquire();
        final TestVector v2 = pool.acquire();
        final TestVector v3 = pool.acquire();

        pool.release(v1);
        pool.release(v2);
        pool.release(v3); // exceeds capacity -> discarded

        assertEquals(2, pool.currentThreadLocalPoolSize());
    }
}
