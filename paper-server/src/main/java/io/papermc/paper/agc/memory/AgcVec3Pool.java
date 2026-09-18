package io.papermc.paper.agc.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Allocation-Free Mutable Vector & Bounding Box Scratch Pool.
 *
 * <p>In servers with 5,000+ entities, physics simulation, explosion raycasting,
 * and entity navigation allocate tens of millions of short-lived vector objects per minute.
 * This pool provides thread-local zero-allocation mutable scratch vectors and bounding boxes.</p>
 */
public final class AgcVec3Pool {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcVec3Pool.class);
    private static final AgcVec3Pool INSTANCE = new AgcVec3Pool();

    private static final ThreadLocal<MutableVec3> SCRATCH_VEC = ThreadLocal.withInitial(MutableVec3::new);
    private static final ThreadLocal<MutableAABB> SCRATCH_AABB = ThreadLocal.withInitial(MutableAABB::new);

    private final AtomicLong vectorAcquires = new AtomicLong();
    private final AtomicLong aabbAcquires = new AtomicLong();

    public static AgcVec3Pool get() {
        return INSTANCE;
    }

    private AgcVec3Pool() {}

    /**
     * Obtains a thread-local zero-allocation mutable vector, reset to (0, 0, 0).
     */
    public MutableVec3 getScratchVec() {
        this.vectorAcquires.incrementAndGet();
        return SCRATCH_VEC.get().set(0.0, 0.0, 0.0);
    }

    /**
     * Obtains a thread-local zero-allocation mutable vector initialized to specific coordinates.
     */
    public MutableVec3 getScratchVec(final double x, final double y, final double z) {
        this.vectorAcquires.incrementAndGet();
        return SCRATCH_VEC.get().set(x, y, z);
    }

    /**
     * Obtains a thread-local zero-allocation mutable bounding box initialized to specified bounds.
     */
    public MutableAABB getScratchAabb(
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ
    ) {
        this.aabbAcquires.incrementAndGet();
        return SCRATCH_AABB.get().set(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void clearMetrics() {
        this.vectorAcquires.set(0);
        this.aabbAcquires.set(0);
    }

    public PoolMetrics metrics() {
        return new PoolMetrics(
            this.vectorAcquires.get(),
            this.aabbAcquires.get()
        );
    }

    /**
     * Mutable Vector with zero-allocation in-place arithmetic.
     */
    public static final class MutableVec3 {
        public double x;
        public double y;
        public double z;

        public MutableVec3() {
            this(0.0, 0.0, 0.0);
        }

        public MutableVec3(final double x, final double y, final double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public MutableVec3 set(final double x, final double y, final double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public MutableVec3 add(final double dx, final double dy, final double dz) {
            this.x += dx;
            this.y += dy;
            this.z += dz;
            return this;
        }

        public MutableVec3 scale(final double factor) {
            this.x *= factor;
            this.y *= factor;
            this.z *= factor;
            return this;
        }

        public double lengthSquared() {
            return this.x * this.x + this.y * this.y + this.z * this.z;
        }

        public double length() {
            return Math.sqrt(lengthSquared());
        }

        public MutableVec3 normalize() {
            final double len = length();
            if (len > 1.0E-4) {
                this.x /= len;
                this.y /= len;
                this.z /= len;
            }
            return this;
        }

        public double dot(final double ox, final double oy, final double oz) {
            return this.x * ox + this.y * oy + this.z * oz;
        }
    }

    /**
     * Mutable AABB for zero-allocation collision & intersection tests.
     */
    public static final class MutableAABB {
        public double minX, minY, minZ;
        public double maxX, maxY, maxZ;

        public MutableAABB() {
            this(0, 0, 0, 0, 0, 0);
        }

        public MutableAABB(
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ
        ) {
            set(minX, minY, minZ, maxX, maxY, maxZ);
        }

        public MutableAABB set(
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ
        ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            return this;
        }

        public boolean intersects(
            final double otherMinX, final double otherMinY, final double otherMinZ,
            final double otherMaxX, final double otherMaxY, final double otherMaxZ
        ) {
            return this.minX < otherMaxX && this.maxX > otherMinX &&
                   this.minY < otherMaxY && this.maxY > otherMinY &&
                   this.minZ < otherMaxZ && this.maxZ > otherMinZ;
        }

        public MutableAABB inflate(final double dx, final double dy, final double dz) {
            this.minX -= dx;
            this.minY -= dy;
            this.minZ -= dz;
            this.maxX += dx;
            this.maxY += dy;
            this.maxZ += dz;
            return this;
        }
    }

    public record PoolMetrics(
        long vectorAcquires,
        long aabbAcquires
    ) {
    }
}
