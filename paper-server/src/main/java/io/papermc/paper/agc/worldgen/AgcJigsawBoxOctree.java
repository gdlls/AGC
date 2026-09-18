package io.papermc.paper.agc.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Jigsaw Piece Intersection Box-Octree (Structure Layout Optimizer port).
 *
 * <p>Vanilla Jigsaw placement ({@code JigsawPlacement.Placer}) keeps the remaining free
 * volume as a {@code VoxelShape} and tests every candidate piece with
 * {@code Shapes.joinIsNotEmpty(free, piece, ONLY_SECOND)}. The VoxelShape vertex list
 * grows with every placed piece, so intersection checks degrade toward O(n^2) for
 * large recursive structures (trial chambers, ancient cities, mineshafts).</p>
 *
 * <p>This octree stores placed-piece AABBs in a loose 3D octree and answers the same
 * question — "does this candidate box overlap any placed piece?" — by visiting only
 * spatially nearby leaves. Decisions are <b>bit-identical</b> to the VoxelShape test:
 * strict interior overlap on all three axes, matching
 * {@code AABB.of(box).deflate(0.25)} semantics used by vanilla. The octree never
 * accepts a piece vanilla would reject, and never rejects one vanilla would accept.</p>
 *
 * <p>Thread-safety: one instance per placement run (Jigsaw placement is single-threaded
 * per structure start). Concurrent use is guarded by the caller.</p>
 */
public final class AgcJigsawBoxOctree {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcJigsawBoxOctree.class);
    private static final AgcJigsawBoxOctree SHARED = new AgcJigsawBoxOctree(8);

    /** Max boxes per leaf before splitting. */
    private static final int LEAF_CAPACITY = 8;
    /** Max octree depth. Depth 8 covers 256x256x256 at 1-block leaves. */
    private static final int MAX_DEPTH = 10;

    private Node root;
    private int size;
    // Explicit tracked bounds: always contain every inserted box (root node bounds
    // never grow, so they cannot be used for rejection — see overlapsAny).
    private double boundMinX = Double.POSITIVE_INFINITY;
    private double boundMinY = Double.POSITIVE_INFINITY;
    private double boundMinZ = Double.POSITIVE_INFINITY;
    private double boundMaxX = Double.NEGATIVE_INFINITY;
    private double boundMaxY = Double.NEGATIVE_INFINITY;
    private double boundMaxZ = Double.NEGATIVE_INFINITY;

    private final AtomicLong overlapQueries = new AtomicLong();
    private final AtomicLong nodesVisited = new AtomicLong();
    private final AtomicLong boxesTested = new AtomicLong();
    private final AtomicLong earlyRejects = new AtomicLong();

    /** Cross-instance live counters (NMS uses per-lineage instances; these prove live activation). */
    private static final AtomicLong GLOBAL_FITS_CALLS = new AtomicLong();
    private static final AtomicLong GLOBAL_OVERLAP_HITS = new AtomicLong();

    public static long globalFitsCalls() {
        return GLOBAL_FITS_CALLS.get();
    }

    public static long globalOverlapHits() {
        return GLOBAL_OVERLAP_HITS.get();
    }

    public static AgcJigsawBoxOctree shared() {
        return SHARED;
    }

    public AgcJigsawBoxOctree() {
        this(MAX_DEPTH);
    }

    AgcJigsawBoxOctree(final int maxDepth) {
        this.maxDepth = maxDepth;
    }

    private final int maxDepth;

    /**
     * Resets the octree to cover the given outer bounds (the initial free volume).
     */
    public synchronized void reset(
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ
    ) {
        this.root = new Node(minX, minY, minZ, maxX, maxY, maxZ, 0);
        this.size = 0;
        this.boundMinX = Double.POSITIVE_INFINITY;
        this.boundMinY = Double.POSITIVE_INFINITY;
        this.boundMinZ = Double.POSITIVE_INFINITY;
        this.boundMaxX = Double.NEGATIVE_INFINITY;
        this.boundMaxY = Double.NEGATIVE_INFINITY;
        this.boundMaxZ = Double.NEGATIVE_INFINITY;
    }

    /**
     * Records a newly placed piece box.
     */
    public synchronized void insert(
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ
    ) {
        if (this.root == null) {
            this.reset(minX, minY, minZ, maxX, maxY, maxZ);
        }
        this.root.insert(new Box(minX, minY, minZ, maxX, maxY, maxZ));
        this.size++;
        if (minX < this.boundMinX) {
            this.boundMinX = minX;
        }
        if (minY < this.boundMinY) {
            this.boundMinY = minY;
        }
        if (minZ < this.boundMinZ) {
            this.boundMinZ = minZ;
        }
        if (maxX > this.boundMaxX) {
            this.boundMaxX = maxX;
        }
        if (maxY > this.boundMaxY) {
            this.boundMaxY = maxY;
        }
        if (maxZ > this.boundMaxZ) {
            this.boundMaxZ = maxZ;
        }
    }

    /**
     * Returns {@code true} if the candidate box strictly overlaps any placed piece.
     * Matches vanilla: overlap requires {@code min < max} interior intersection on
     * all three axes (touching faces do NOT count).
     */
    public boolean overlapsAny(
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ
    ) {
        this.overlapQueries.incrementAndGet();
        final Node snapshot = this.root;
        if (snapshot == null || this.size == 0) {
            return false;
        }
        // Fast outer-bounds reject against TRACKED bounds (which always contain
        // every inserted box): a candidate fully outside cannot overlap anything.
        if (maxX <= this.boundMinX || minX >= this.boundMaxX
            || maxY <= this.boundMinY || minY >= this.boundMaxY
            || maxZ <= this.boundMinZ || minZ >= this.boundMaxZ) {
            this.earlyRejects.incrementAndGet();
            return false;
        }
        final boolean hit = snapshot.overlaps(minX, minY, minZ, maxX, maxY, maxZ, this);
        if (hit) {
            GLOBAL_OVERLAP_HITS.incrementAndGet();
        }
        return hit;
    }

    /**
     * Vanilla-equivalent fit test: the candidate (already deflated by 0.25 like
     * vanilla's {@code AABB.of(targetBB).deflate(0.25)}) fits iff it overlaps no
     * placed piece. Outer-bounds containment must be checked by the caller against
     * the free-shape bounds (same as the existing AGC containment fast path).
     */
    public boolean fits(
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ
    ) {
        GLOBAL_FITS_CALLS.incrementAndGet();
        return !this.overlapsAny(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public synchronized int size() {
        return this.size;
    }

    public synchronized void clear() {
        this.root = null;
        this.size = 0;
        this.boundMinX = Double.POSITIVE_INFINITY;
        this.boundMinY = Double.POSITIVE_INFINITY;
        this.boundMinZ = Double.POSITIVE_INFINITY;
        this.boundMaxX = Double.NEGATIVE_INFINITY;
        this.boundMaxY = Double.NEGATIVE_INFINITY;
        this.boundMaxZ = Double.NEGATIVE_INFINITY;
        this.overlapQueries.set(0);
        this.nodesVisited.set(0);
        this.boxesTested.set(0);
        this.earlyRejects.set(0);
    }

    void recordVisit() {
        this.nodesVisited.incrementAndGet();
    }

    void recordBoxTest() {
        this.boxesTested.incrementAndGet();
    }

    public OctreeMetrics metrics() {
        return new OctreeMetrics(
            this.size,
            this.overlapQueries.get(),
            this.nodesVisited.get(),
            this.boxesTested.get(),
            this.earlyRejects.get()
        );
    }

    public record OctreeMetrics(
        int placedBoxes,
        long overlapQueries,
        long nodesVisited,
        long boxesTested,
        long earlyRejects
    ) {}


    private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean overlapsStrict(
            final double qMinX, final double qMinY, final double qMinZ,
            final double qMaxX, final double qMaxY, final double qMaxZ
        ) {
            return this.minX < qMaxX && this.maxX > qMinX
                && this.minY < qMaxY && this.maxY > qMinY
                && this.minZ < qMaxZ && this.maxZ > qMinZ;
        }

        boolean fitsIn(
            final double nMinX, final double nMinY, final double nMinZ,
            final double nMaxX, final double nMaxY, final double nMaxZ
        ) {
            return this.minX >= nMinX && this.maxX <= nMaxX
                && this.minY >= nMinY && this.maxY <= nMaxY
                && this.minZ >= nMinZ && this.maxZ <= nMaxZ;
        }
    }

    private final class Node {
        final double minX;
        final double minY;
        final double minZ;
        final double maxX;
        final double maxY;
        final double maxZ;
        final int depth;
        List<Box> boxes;
        Node[] children;

        Node(
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ,
            final int depth
        ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.depth = depth;
            this.boxes = new ArrayList<>(LEAF_CAPACITY);
        }

        void insert(final Box box) {
            if (this.children != null) {
                final int slot = this.fittingChild(box);
                if (slot >= 0) {
                    this.children[slot].insert(box);
                    return;
                }
                this.boxes.add(box);
                return;
            }
            this.boxes.add(box);
            if (this.boxes.size() > LEAF_CAPACITY && this.depth < AgcJigsawBoxOctree.this.maxDepth) {
                this.split();
            }
        }

        boolean overlaps(
            final double qMinX, final double qMinY, final double qMinZ,
            final double qMaxX, final double qMaxY, final double qMaxZ,
            final AgcJigsawBoxOctree owner
        ) {
            owner.recordVisit();
            // NOTE: no bounds prune at this level — boxes stored loosely at a node
            // are only guaranteed to overlap the node when they were routed here by
            // fittingChild, and the ROOT may hold boxes outside its own bounds.
            // Pruning happens per-child below (child boxes always fit child bounds).
            for (int i = 0, n = this.boxes.size(); i < n; i++) {
                owner.recordBoxTest();
                if (this.boxes.get(i).overlapsStrict(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ)) {
                    return true;
                }
            }
            if (this.children != null) {
                for (final Node child : this.children) {
                    if (qMaxX <= child.minX || qMinX >= child.maxX
                        || qMaxY <= child.minY || qMinY >= child.maxY
                        || qMaxZ <= child.minZ || qMinZ >= child.maxZ) {
                        continue;
                    }
                    if (child.overlaps(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ, owner)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private int fittingChild(final Box box) {
            final double midX = (this.minX + this.maxX) * 0.5;
            final double midY = (this.minY + this.maxY) * 0.5;
            final double midZ = (this.minZ + this.maxZ) * 0.5;
            for (int i = 0; i < 8; i++) {
                final double cMinX = (i & 1) == 0 ? this.minX : midX;
                final double cMaxX = (i & 1) == 0 ? midX : this.maxX;
                final double cMinY = (i & 2) == 0 ? this.minY : midY;
                final double cMaxY = (i & 2) == 0 ? midY : this.maxY;
                final double cMinZ = (i & 4) == 0 ? this.minZ : midZ;
                final double cMaxZ = (i & 4) == 0 ? midZ : this.maxZ;
                if (box.fitsIn(cMinX, cMinY, cMinZ, cMaxX, cMaxY, cMaxZ)) {
                    return i;
                }
            }
            return -1;
        }

        private void split() {
            final double midX = (this.minX + this.maxX) * 0.5;
            final double midY = (this.minY + this.maxY) * 0.5;
            final double midZ = (this.minZ + this.maxZ) * 0.5;
            this.children = new Node[8];
            for (int i = 0; i < 8; i++) {
                this.children[i] = new Node(
                    (i & 1) == 0 ? this.minX : midX,
                    (i & 2) == 0 ? this.minY : midY,
                    (i & 4) == 0 ? this.minZ : midZ,
                    (i & 1) == 0 ? midX : this.maxX,
                    (i & 2) == 0 ? midY : this.maxY,
                    (i & 4) == 0 ? midZ : this.maxZ,
                    this.depth + 1
                );
            }
            final List<Box> kept = new ArrayList<>(this.boxes.size());
            for (final Box box : this.boxes) {
                final int slot = this.fittingChild(box);
                if (slot >= 0) {
                    this.children[slot].insert(box);
                } else {
                    kept.add(box);
                }
            }
            this.boxes = kept;
        }
    }
}
