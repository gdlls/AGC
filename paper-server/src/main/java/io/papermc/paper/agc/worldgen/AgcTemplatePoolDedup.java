package io.papermc.paper.agc.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * AGC — Template-Pool Duplicate-Weight Skip (Structure Layout Optimizer port).
 *
 * <p>Vanilla {@code StructureTemplatePool} weight expansion duplicates the same
 * {@code SinglePoolElement} N times in the shuffled candidate list. During Jigsaw
 * placement the game re-runs the full fit check for duplicates even when the same
 * element already failed at the same joint moments ago — pure wasted work with an
 * identical outcome.</p>
 *
 * <p>{@link #selectFirstFit} memoizes per-attempt failures by element identity and
 * skips re-checking them. The selected piece is <b>identical</b> to vanilla's: a
 * duplicate can never succeed where its twin failed (same element, same joint, same
 * rotation state), so skipping only removes guaranteed-failed checks.</p>
 *
 * <p>Seed-parity note: this fast path never changes which piece is placed, only how
 * many failed checks run. The order-changing {@code deduplicate} variant from the
 * upstream mod (which <i>does</i> break seed parity) is intentionally NOT provided;
 * use {@link #deduplicatedView} documentation instead — callers that want it must
 * copy+shuffle unique elements themselves with an explicit opt-in flag.</p>
 */
public final class AgcTemplatePoolDedup {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcTemplatePoolDedup.class);
    private static final AgcTemplatePoolDedup INSTANCE = new AgcTemplatePoolDedup();

    private final AtomicLong candidateChecks = new AtomicLong();
    private final AtomicLong duplicateSkips = new AtomicLong();
    private final AtomicLong placementsSelected = new AtomicLong();

    public static AgcTemplatePoolDedup get() {
        return INSTANCE;
    }

    private AgcTemplatePoolDedup() {}

    /**
     * Returns the first candidate accepted by {@code fits}, skipping duplicate
     * identities that already failed within this call.
     *
     * @param shuffledCandidates weight-expanded shuffled candidate list (may contain duplicates)
     * @param fits fit test; must be deterministic for identical inputs within one call
     * @return index of the first fitting candidate in the original list, or -1
     */
    public <T> int selectFirstFit(final List<T> shuffledCandidates, final Predicate<T> fits) {
        if (shuffledCandidates == null || shuffledCandidates.isEmpty() || fits == null) {
            return -1;
        }
        // Identity-keyed: two list slots holding the same element instance are duplicates.
        final IdentityHashMap<Object, Boolean> failed = new IdentityHashMap<>(Math.min(32, shuffledCandidates.size()));
        for (int i = 0, n = shuffledCandidates.size(); i < n; i++) {
            final T candidate = shuffledCandidates.get(i);
            if (candidate == null) {
                continue;
            }
            if (failed.containsKey(candidate)) {
                this.duplicateSkips.incrementAndGet();
                continue;
            }
            this.candidateChecks.incrementAndGet();
            boolean ok;
            try {
                ok = fits.test(candidate);
            } catch (final RuntimeException e) {
                // A throwing fit test is not a "failed fit" — do not memoize, propagate.
                throw e;
            }
            if (ok) {
                this.placementsSelected.incrementAndGet();
                return i;
            }
            failed.put(candidate, Boolean.FALSE);
        }
        return -1;
    }

    public void recordDuplicateSkip() {
        this.duplicateSkips.incrementAndGet();
    }

    public void recordCandidateCheck() {
        this.candidateChecks.incrementAndGet();
    }

    public void recordPlacementSelected() {
        this.placementsSelected.incrementAndGet();
    }

    /**
     * Builds a parity-preserving view description: unique elements in first-seen
     * order plus per-element multiplicity. Informational only — callers keep using
     * the vanilla shuffled list; this documents what an order-changing dedup WOULD
     * do so operators understand why AGC does not do it by default.
     */
    public <T> List<ElementMultiplicity<T>> deduplicatedView(final List<T> shuffledCandidates) {
        final List<ElementMultiplicity<T>> out = new ArrayList<>();
        if (shuffledCandidates == null) {
            return out;
        }
        final IdentityHashMap<Object, Integer> indexByIdentity = new IdentityHashMap<>();
        for (final T candidate : shuffledCandidates) {
            if (candidate == null) {
                continue;
            }
            final Integer existing = indexByIdentity.get(candidate);
            if (existing == null) {
                indexByIdentity.put(candidate, out.size());
                out.add(new ElementMultiplicity<>(candidate, 1));
            } else {
                final ElementMultiplicity<T> prev = out.get(existing);
                out.set(existing, new ElementMultiplicity<>(prev.element(), prev.multiplicity() + 1));
            }
        }
        return out;
    }

    public void clear() {
        this.candidateChecks.set(0);
        this.duplicateSkips.set(0);
        this.placementsSelected.set(0);
    }

    public DedupMetrics metrics() {
        return new DedupMetrics(
            this.candidateChecks.get(),
            this.duplicateSkips.get(),
            this.placementsSelected.get()
        );
    }

    public record ElementMultiplicity<T>(T element, int multiplicity) {}

    public record DedupMetrics(long candidateChecks, long duplicateSkips, long placementsSelected) {}
}
