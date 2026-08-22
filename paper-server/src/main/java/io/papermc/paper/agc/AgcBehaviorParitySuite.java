package io.papermc.paper.agc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGC — Vanilla Behavioral Equivalence & Parity Invariant Suite (Roadmap Phase 5).
 *
 * <p>Under AGC's parallel world execution and aggressive optimizations, minor tick-order
 * deviations are permissible if and only if they maintain <b>Behavioral Equivalence</b>
 * (the emergent game state is indistinguishable from Vanilla Paper).</p>
 *
 * <p>This suite defines and verifies invariant contracts across:
 * <ul>
 *   <li>Redstone circuit propagation order</li>
 *   <li>Entity movement & collision resolution</li>
 *   <li>Cross-dimension portal transfer causality</li>
 *   <li>Block change event notification ordering</li>
 * </ul>
 * </p>
 */
public final class AgcBehaviorParitySuite {

    private static final AgcBehaviorParitySuite INSTANCE = new AgcBehaviorParitySuite();

    public enum ParityCategory {
        REDSTONE_DETERMINISM,
        ENTITY_PHYSICS,
        CROSS_WORLD_CAUSALITY,
        BLOCK_MUTATION_ORDER,
        PLUGIN_EVENT_INTEGRITY
    }

    public enum DeviationPolicy {
        /** Strict identical tick order required (no deviation allowed) */
        STRICT_ORDER_PRESERVED,
        /** Tick order may vary if end-of-tick causal state is equivalent */
        CAUSALLY_EQUIVALENT,
        /** Tolerant of non-gameplay cosmetic jitter (e.g. particle/light delays) */
        COSMETIC_TOLERANT
    }

    private final Map<ParityCategory, ParityRule> registeredRules = new ConcurrentHashMap<>();

    // Telemetry & metrics
    private final AtomicLong checksExecuted = new AtomicLong();
    private final AtomicLong deviationsAllowed = new AtomicLong();
    private final AtomicLong violationsDetected = new AtomicLong();

    public static AgcBehaviorParitySuite get() {
        return INSTANCE;
    }

    public AgcBehaviorParitySuite() {
        // Register default vanilla parity invariants
        registerRule(ParityCategory.REDSTONE_DETERMINISM, DeviationPolicy.STRICT_ORDER_PRESERVED, "Redstone wire and quasi-connectivity must resolve deterministically within world tick");
        registerRule(ParityCategory.ENTITY_PHYSICS, DeviationPolicy.CAUSALLY_EQUIVALENT, "Entity collisions and velocity updates must match vanilla bounds");
        registerRule(ParityCategory.CROSS_WORLD_CAUSALITY, DeviationPolicy.CAUSALLY_EQUIVALENT, "Player and entity portal transits must preserve inventory and health across boundary");
        registerRule(ParityCategory.BLOCK_MUTATION_ORDER, DeviationPolicy.CAUSALLY_EQUIVALENT, "Block changes must be committed before downstream neighbor updates");
        registerRule(ParityCategory.PLUGIN_EVENT_INTEGRITY, DeviationPolicy.STRICT_ORDER_PRESERVED, "Bukkit synchronous events must be observed in strict sequential order");
    }

    public void registerRule(final ParityCategory category, final DeviationPolicy policy, final String description) {
        this.registeredRules.put(category, new ParityRule(category, policy, description));
    }

    /**
     * Asserts that a behavioral transition conforms to the defined parity policy.
     *
     * @param category Parity category under evaluation
     * @param isStrictlyEqual Whether raw execution matched vanilla tick-for-tick
     * @param isCausallyEquivalent Whether final world state matches vanilla expectation
     * @return true if compliant with parity policy, false if invariant violated
     */
    public boolean verifyTransition(
        final ParityCategory category,
        final boolean isStrictlyEqual,
        final boolean isCausallyEquivalent
    ) {
        this.checksExecuted.incrementAndGet();
        final ParityRule rule = this.registeredRules.get(category);
        if (rule == null) {
            return true;
        }

        switch (rule.policy()) {
            case STRICT_ORDER_PRESERVED -> {
                if (isStrictlyEqual) {
                    return true;
                }
                this.violationsDetected.incrementAndGet();
                return false;
            }
            case CAUSALLY_EQUIVALENT -> {
                if (isStrictlyEqual) {
                    return true;
                }
                if (isCausallyEquivalent) {
                    this.deviationsAllowed.incrementAndGet();
                    return true;
                }
                this.violationsDetected.incrementAndGet();
                return false;
            }
            case COSMETIC_TOLERANT -> {
                this.deviationsAllowed.incrementAndGet();
                return true;
            }
        }
        return true;
    }

    public ParityRule getRule(final ParityCategory category) {
        return this.registeredRules.get(category);
    }

    public List<ParityRule> getAllRules() {
        return Collections.unmodifiableList(new ArrayList<>(this.registeredRules.values()));
    }

    public void resetMetrics() {
        this.checksExecuted.set(0);
        this.deviationsAllowed.set(0);
        this.violationsDetected.set(0);
    }

    public ParityMetrics metrics() {
        return new ParityMetrics(
            this.registeredRules.size(),
            this.checksExecuted.get(),
            this.deviationsAllowed.get(),
            this.violationsDetected.get()
        );
    }

    public record ParityRule(
        ParityCategory category,
        DeviationPolicy policy,
        String description
    ) {}

    public record ParityMetrics(
        int totalRules,
        long checksExecuted,
        long deviationsAllowed,
        long violationsDetected
    ) {
        public boolean isFullyCompliant() {
            return this.violationsDetected == 0;
        }
    }
}
