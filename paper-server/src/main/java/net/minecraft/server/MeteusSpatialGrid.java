package net.minecraft.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Ray;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * AGC spatial index and compatibility façade.
 * <p>
 * This class is intentionally Bukkit-facing and conservative: it never changes
 * vanilla entity semantics by itself. Integrations can opt in to use it for
 * nearby-entity, raycast and dense-player lookup acceleration while preserving
 * the Paper/Bukkit API surface. The historical Meteus name remains available so
 * existing diagnostics and test plugins do not break during the AGC rename.
 */
public class MeteusSpatialGrid {
    public static final MeteusSpatialGrid INSTANCE = new MeteusSpatialGrid();

    private static final int DEFAULT_CELL_SIZE = 16;
    private static final int DEFAULT_HISTORY_LIMIT = 20;

    public final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    public volatile int bufferLimit = DEFAULT_HISTORY_LIMIT;
    public volatile double extrapolationRatio = 0.25D;
    public volatile double collisionAspectRatio = 1.0D;
    public volatile double maxHitboxExpansion = 1.5D;
    public volatile int allocatedNodeCount = 0;
    public volatile double preallocationRatio = 1.25D;
    public volatile boolean debugMode = false;
    public volatile String metricsLogPath = "logs/agc-metrics.log";
    public volatile String configFilePath = "config/agc-performance.yml";
    public volatile long configLastModified = 0L;

    private final int cellSize;
    private final Map<UUID, Node> nodesByEntity = new ConcurrentHashMap<>();
    private final Map<CellKey, Set<UUID>> entitiesByCell = new HashMap<>();
    private final Map<EntityType, Hitbox> customHitboxes = new ConcurrentHashMap<>();
    private final ArrayDeque<Node> nodePool = new ArrayDeque<>();
    private final AtomicLong queryCount = new AtomicLong();
    private final AtomicLong totalQueryNanos = new AtomicLong();
    private volatile Predicate<Entity> raycastFilter = entity -> true;
    private volatile boolean verboseLogging = false;

    public MeteusSpatialGrid() {
        this(DEFAULT_CELL_SIZE);
    }

    public MeteusSpatialGrid(final int cellSize) {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize must be positive");
        }
        this.cellSize = cellSize;
    }

    public int getCellSize() {
        return this.cellSize;
    }

    public boolean isWithinBounds(final double x, final double y, final double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && y >= -2048.0D && y <= 4096.0D;
    }

    public int getEntityCount() {
        return this.nodesByEntity.size();
    }

    public void insertEntity(final Entity entity) {
        if (entity == null) {
            return;
        }
        final Location location = entity.getLocation();
        this.updateEntityLocation(entity, location.getX(), location.getY(), location.getZ());
    }

    public void removeEntity(final Entity entity) {
        if (entity == null) {
            return;
        }
        this.readWriteLock.writeLock().lock();
        try {
            final Node removed = this.nodesByEntity.remove(entity.getUniqueId());
            if (removed != null) {
                this.removeFromCell(removed.cellKey, removed.uuid);
                this.releaseNode(removed);
            }
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    public void updateEntityLocation(final Entity entity, final double x, final double y, final double z) {
        if (entity == null || !this.isWithinBounds(x, y, z)) {
            return;
        }
        this.readWriteLock.writeLock().lock();
        try {
            final UUID uuid = entity.getUniqueId();
            final CellKey newKey = this.cellKey(entity.getWorld(), x, y, z);
            Node node = this.nodesByEntity.get(uuid);
            if (node == null) {
                node = (Node) this.allocateNode();
                node.uuid = uuid;
                node.entity = entity;
                node.worldName = safeWorldName(entity.getWorld());
                this.nodesByEntity.put(uuid, node);
            } else if (!Objects.equals(node.cellKey, newKey)) {
                this.removeFromCell(node.cellKey, uuid);
            }
            node.worldName = safeWorldName(entity.getWorld());
            node.record(x, y, z, newKey, System.nanoTime(), Math.max(2, this.bufferLimit));
            this.entitiesByCell.computeIfAbsent(newKey, ignored -> new HashSet<>()).add(uuid);
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    public List<Entity> getEntitiesInRadius(final Location center, final double radius) {
        if (center == null || radius < 0.0D || center.getWorld() == null) {
            return Collections.emptyList();
        }
        final long start = System.nanoTime();
        this.readWriteLock.readLock().lock();
        try {
            final double radiusSquared = radius * radius;
            final int cellRadius = (int) Math.ceil(radius / this.cellSize);
            final CellKey base = this.cellKey(center.getWorld(), center.getX(), center.getY(), center.getZ());
            final ArrayList<Entity> result = new ArrayList<>();
            for (int dx = -cellRadius; dx <= cellRadius; dx++) {
                for (int dy = -cellRadius; dy <= cellRadius; dy++) {
                    for (int dz = -cellRadius; dz <= cellRadius; dz++) {
                        final Set<UUID> bucket = this.entitiesByCell.get(base.offset(dx, dy, dz));
                        if (bucket == null || bucket.isEmpty()) {
                            continue;
                        }
                        for (final UUID uuid : bucket) {
                            final Node node = this.nodesByEntity.get(uuid);
                            if (node == null || node.entity == null) {
                                continue;
                            }
                            if (!Objects.equals(node.worldName, safeWorldName(center.getWorld()))) {
                                continue;
                            }
                            final double sx = node.x - center.getX();
                            final double sy = node.y - center.getY();
                            final double sz = node.z - center.getZ();
                            if ((sx * sx) + (sy * sy) + (sz * sz) <= radiusSquared) {
                                result.add(node.entity);
                            }
                        }
                    }
                }
            }
            return result;
        } finally {
            this.readWriteLock.readLock().unlock();
            this.queryCount.incrementAndGet();
            this.totalQueryNanos.addAndGet(System.nanoTime() - start);
        }
    }

    public void clearAll() {
        this.readWriteLock.writeLock().lock();
        try {
            this.nodesByEntity.clear();
            this.entitiesByCell.clear();
            this.nodePool.clear();
            this.allocatedNodeCount = 0;
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    public int getInterval() {
        return 1;
    }

    public double getRaycastPrecision() {
        return 0.125D;
    }

    public RayTraceResult calculateIntersection(final Ray ray, final double maxDistance) {
        if (ray == null || maxDistance < 0.0D) {
            return null;
        }
        final Vector origin = ray.getOrigin();
        final Vector direction = ray.getDirection();
        RayTraceResult best = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        this.readWriteLock.readLock().lock();
        try {
            for (final Node node : this.nodesByEntity.values()) {
                if (node.entity == null || !this.raycastFilter.test(node.entity)) {
                    continue;
                }
                final RayTraceResult hit = this.expandedBox(node.entity).rayTrace(origin, direction, maxDistance);
                if (hit != null) {
                    final double distanceSquared = hit.getHitPosition().distanceSquared(origin);
                    if (distanceSquared < bestDistanceSquared) {
                        bestDistanceSquared = distanceSquared;
                        best = hit;
                    }
                }
            }
            return best;
        } finally {
            this.readWriteLock.readLock().unlock();
        }
    }

    public Location interpolateLocation(final Entity entity, final double partialTick) {
        if (entity == null) {
            return null;
        }
        final Node node = this.nodesByEntity.get(entity.getUniqueId());
        if (node == null || node.history.size() < 2) {
            return entity.getLocation();
        }
        final Snapshot newest = node.history.peekLast();
        final Snapshot previous = node.previousSnapshot();
        if (newest == null || previous == null) {
            return entity.getLocation();
        }
        final double clamped = Math.max(0.0D, Math.min(1.0D + this.extrapolationRatio, partialTick));
        final double x = previous.x + ((newest.x - previous.x) * clamped);
        final double y = previous.y + ((newest.y - previous.y) * clamped);
        final double z = previous.z + ((newest.z - previous.z) * clamped);
        return new Location(entity.getWorld(), x, y, z, entity.getLocation().getYaw(), entity.getLocation().getPitch());
    }

    public Vector getInterpolatedVelocity(final Entity entity) {
        if (entity == null) {
            return new Vector();
        }
        final Node node = this.nodesByEntity.get(entity.getUniqueId());
        if (node == null || node.history.size() < 2) {
            return entity.getVelocity();
        }
        final Snapshot newest = node.history.peekLast();
        final Snapshot previous = node.previousSnapshot();
        if (newest == null || previous == null) {
            return entity.getVelocity();
        }
        return new Vector(newest.x - previous.x, newest.y - previous.y, newest.z - previous.z);
    }

    public void applyRaycastFilter(final Predicate<Entity> filter) {
        this.raycastFilter = filter != null ? filter : entity -> true;
    }

    public Entity raycastTarget(final LivingEntity source, final double maxDistance) {
        if (source == null || maxDistance < 0.0D) {
            return null;
        }
        final Location eye = source.getEyeLocation();
        final Ray ray = new Ray(eye.toVector(), eye.getDirection());
        Entity best = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        final Collection<Entity> candidates = source.getWorld().getNearbyEntities(eye, maxDistance, maxDistance, maxDistance, entity -> entity != source && this.raycastFilter.test(entity));
        for (final Entity candidate : candidates) {
            final RayTraceResult hit = this.expandedBox(candidate).rayTrace(ray.getOrigin(), ray.getDirection(), maxDistance);
            if (hit == null) {
                continue;
            }
            final double distanceSquared = hit.getHitPosition().distanceSquared(ray.getOrigin());
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = candidate;
            }
        }
        return best;
    }

    public void acquireReadLock() {
        if (!this.readWriteLock.readLock().tryLock()) {
            // Count observable contention for metrics before blocking.
            this.queryCount.incrementAndGet();
            this.readWriteLock.readLock().lock();
        }
    }

    public void releaseReadLock() {
        this.readWriteLock.readLock().unlock();
    }

    public void acquireWriteLock() {
        if (!this.readWriteLock.writeLock().tryLock()) {
            this.queryCount.incrementAndGet();
            this.readWriteLock.writeLock().lock();
        }
    }

    public void releaseWriteLock() {
        this.readWriteLock.writeLock().unlock();
    }

    public <T> T executeLocked(final Callable<T> callable) throws Exception {
        this.acquireReadLock();
        try {
            return callable.call();
        } finally {
            this.releaseReadLock();
        }
    }

    public long getLockContentionCount() {
        return this.queryCount.get();
    }

    public int getThreadPoolCapacity() {
        return ForkJoinPool.getCommonPoolParallelism();
    }

    public boolean isConcurrentQueriesEnabled() {
        return true;
    }

    public double getBoundingBoxScale() {
        return this.collisionAspectRatio;
    }

    public BoundingBox getHitboxExtents(final Entity entity) {
        if (entity == null) {
            return new BoundingBox();
        }
        return this.expandedBox(entity);
    }

    public boolean hasCustomExtents(final Entity entity) {
        return entity != null && this.customHitboxes.containsKey(entity.getType());
    }

    public boolean intersectsRay(final Entity entity, final Ray ray) {
        if (entity == null || ray == null) {
            return false;
        }
        return this.expandedBox(entity).rayTrace(ray.getOrigin(), ray.getDirection(), 256.0D) != null;
    }

    public void registerCustomHitbox(final EntityType type, final double width, final double height) {
        if (type == null) {
            return;
        }
        this.customHitboxes.put(type, new Hitbox(Math.min(width, this.maxHitboxExpansion), Math.min(height, this.maxHitboxExpansion)));
    }

    public void unregisterCustomHitbox(final EntityType type) {
        if (type != null) {
            this.customHitboxes.remove(type);
        }
    }

    public List<Entity> resolveOverlappingHitboxes(final Ray ray, final double maxDistance) {
        if (ray == null || maxDistance < 0.0D) {
            return Collections.emptyList();
        }
        final ArrayList<Entity> result = new ArrayList<>();
        this.readWriteLock.readLock().lock();
        try {
            for (final Node node : this.nodesByEntity.values()) {
                if (node.entity != null && this.intersectsRay(node.entity, ray)) {
                    final RayTraceResult hit = this.expandedBox(node.entity).rayTrace(ray.getOrigin(), ray.getDirection(), maxDistance);
                    if (hit != null) {
                        result.add(node.entity);
                    }
                }
            }
            return result;
        } finally {
            this.readWriteLock.readLock().unlock();
        }
    }

    public Object allocateNode() {
        this.readWriteLock.writeLock().lock();
        try {
            final Node pooled = this.nodePool.pollFirst();
            if (pooled != null) {
                return pooled.reset();
            }
            this.allocatedNodeCount++;
            return new Node();
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    public void releaseNode(final Object node) {
        if (!(node instanceof Node pooled)) {
            return;
        }
        this.readWriteLock.writeLock().lock();
        try {
            pooled.reset();
            this.nodePool.addLast(pooled);
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    public void preallocateNodes(final int count) {
        if (count <= 0) {
            return;
        }
        this.readWriteLock.writeLock().lock();
        try {
            for (int i = 0; i < count; i++) {
                this.nodePool.addLast(new Node());
                this.allocatedNodeCount++;
            }
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    public void resizePool(final int targetSize) {
        this.readWriteLock.writeLock().lock();
        try {
            while (this.nodePool.size() > targetSize) {
                this.nodePool.pollLast();
                this.allocatedNodeCount = Math.max(0, this.allocatedNodeCount - 1);
            }
            while (this.nodePool.size() < targetSize) {
                this.nodePool.addLast(new Node());
                this.allocatedNodeCount++;
            }
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }

    public void runPoolGC() {
        this.resizePool(Math.max(0, this.nodesByEntity.size() / 2));
    }

    public int getBufferCapacity() {
        return Math.max(this.bufferLimit, this.nodesByEntity.size());
    }

    public int getPoolNodeSize() {
        return this.nodePool.size();
    }

    public String generateMetricsReport() {
        final long queries = this.queryCount.get();
        final double avgMicros = queries == 0L ? 0.0D : (this.totalQueryNanos.get() / 1000.0D) / queries;
        return "AGC SpatialGrid{entities=" + this.getEntityCount() + ", cells=" + this.entitiesByCell.size() + ", avgQueryMicros=" + avgMicros + '}';
    }

    public double getAverageLatencyMetrics() {
        final long queries = this.queryCount.get();
        return queries == 0L ? 0.0D : (this.totalQueryNanos.get() / 1_000_000.0D) / queries;
    }

    public long getMetricsInterval() {
        return 60_000L;
    }

    public boolean isVerboseLogging() {
        return this.verboseLogging;
    }

    public void setVerboseLogging(final boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
    }

    public void resetMetrics() {
        this.queryCount.set(0L);
        this.totalQueryNanos.set(0L);
    }

    public void visualizeGridAround(final Player player) {
        if (player != null) {
            player.sendMessage(this.generateMetricsReport());
        }
    }

    public int getConfigSchemaVersion() {
        return 26_002;
    }

    public boolean isAutoReloadEnabled() {
        return true;
    }

    public void reloadConfiguration() {
        final Path path = Paths.get(this.configFilePath);
        try {
            this.configLastModified = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (final Exception ignored) {
            this.configLastModified = 0L;
        }
    }

    public void saveConfiguration() {
        final Path path = Paths.get(this.configFilePath);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, "# AGC 26.2 performance defaults\nspatial-grid-cell-size: " + this.cellSize + "\n");
            this.configLastModified = Files.getLastModifiedTime(path).toMillis();
        } catch (final Exception ignored) {
            // Configuration persistence is best-effort for the compatibility façade.
        }
    }

    public void applyConfigDefaults() {
        this.bufferLimit = DEFAULT_HISTORY_LIMIT;
        this.extrapolationRatio = 0.25D;
        this.collisionAspectRatio = 1.0D;
        this.maxHitboxExpansion = 1.5D;
    }

    public boolean validateConfigSyntax() {
        return this.cellSize > 0 && this.bufferLimit >= 2 && this.maxHitboxExpansion >= 0.0D;
    }

    public void recoverCorruptedConfig() {
        this.applyConfigDefaults();
        this.saveConfiguration();
    }

    private CellKey cellKey(final World world, final double x, final double y, final double z) {
        return new CellKey(safeWorldName(world), floorCell(x), floorCell(y), floorCell(z));
    }

    private int floorCell(final double value) {
        return (int) Math.floor(value / this.cellSize);
    }

    private void removeFromCell(final CellKey key, final UUID uuid) {
        if (key == null || uuid == null) {
            return;
        }
        final Set<UUID> bucket = this.entitiesByCell.get(key);
        if (bucket != null) {
            bucket.remove(uuid);
            if (bucket.isEmpty()) {
                this.entitiesByCell.remove(key);
            }
        }
    }

    private BoundingBox expandedBox(final Entity entity) {
        final Hitbox custom = this.customHitboxes.get(entity.getType());
        if (custom == null) {
            final double expand = Math.max(0.0D, Math.min(this.maxHitboxExpansion, this.collisionAspectRatio - 1.0D));
            return entity.getBoundingBox().expand(expand);
        }
        final Location location = entity.getLocation();
        final double halfWidth = custom.width / 2.0D;
        return new BoundingBox(
            location.getX() - halfWidth,
            location.getY(),
            location.getZ() - halfWidth,
            location.getX() + halfWidth,
            location.getY() + custom.height,
            location.getZ() + halfWidth
        );
    }

    private static String safeWorldName(final World world) {
        return world == null ? "" : world.getName();
    }

    private record CellKey(String worldName, int x, int y, int z) {
        CellKey offset(final int dx, final int dy, final int dz) {
            return new CellKey(this.worldName, this.x + dx, this.y + dy, this.z + dz);
        }
    }

    private record Snapshot(double x, double y, double z, long nanoTime) {}

    private record Hitbox(double width, double height) {}

    private static final class Node {
        private UUID uuid;
        private Entity entity;
        private String worldName;
        private CellKey cellKey;
        private double x;
        private double y;
        private double z;
        private final ArrayDeque<Snapshot> history = new ArrayDeque<>();

        Node reset() {
            this.uuid = null;
            this.entity = null;
            this.worldName = null;
            this.cellKey = null;
            this.x = 0.0D;
            this.y = 0.0D;
            this.z = 0.0D;
            this.history.clear();
            return this;
        }

        void record(final double x, final double y, final double z, final CellKey key, final long now, final int limit) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.cellKey = key;
            this.history.addLast(new Snapshot(x, y, z, now));
            while (this.history.size() > limit) {
                this.history.removeFirst();
            }
        }

        Snapshot previousSnapshot() {
            if (this.history.size() < 2) {
                return null;
            }
            final ArrayList<Snapshot> snapshots = new ArrayList<>(this.history);
            return snapshots.get(snapshots.size() - 2);
        }
    }
}
