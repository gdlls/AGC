package io.papermc.paper.agc;

import io.papermc.paper.configuration.GlobalConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * AGC — Real operating-mode policy.
 *
 * <p>The {@code mode} key in {@code config/agc.yml} used to be validated and then ignored: a server
 * configured with {@code mode: vanilla} still ran every AGC fast path, because the per-feature flags
 * are read directly from {@link GlobalConfiguration.Agc.Performance} by production code. That made the
 * key a lie and left operators (and A/B measurements) without a way to obtain the unmodified Paper
 * path.</p>
 *
 * <p>This class makes the key authoritative:</p>
 * <ul>
 *   <li>{@code vanilla} — every AGC performance boolean in the loaded configuration is forced to
 *       {@code false} and every capability-matrix feature is pinned off. The server then takes the
 *       Paper code path everywhere. This is the control arm for differential (AGC vs Paper)
 *       measurements and for support triage.</li>
 *   <li>{@code lossless} / {@code agc_baseline} / {@code unified} — the shipped defaults. Every flag
 *       in this group is documented as behaviour-preserving; anything that changes observable
 *       behaviour is off in the file itself.</li>
 *   <li>{@code agc_aggressive} / {@code experimental} — keeps the file's values, including any
 *       non-lossless opt-ins the operator switched on (parallel world tick, multiverse hibernation,
 *       combat dispatch, entity sleep, chunk send budgeting, …). No extra magic: the file is the
 *       truth.</li>
 * </ul>
 *
 * <p>The reflection sweep is deliberately boring: booleans and the small set of numeric throttles
 * ("neutral" values below) are forced back to their Paper-equivalent values, and a count is logged so
 * an operator can see the mode actually did something.</p>
 */
public final class AgcModePolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcModePolicy.class);

    public static final String VANILLA = "vanilla";
    public static final String LOSSLESS = "lossless";
    public static final String BASELINE = "agc_baseline";
    public static final String UNIFIED = "unified";
    public static final String AGGRESSIVE = "agc_aggressive";
    public static final String EXPERIMENTAL = "experimental";

    private AgcModePolicy() {
    }

    /** Modes accepted by {@code config/agc.yml}. Kept in one place so validation cannot drift. */
    public static boolean isKnownMode(final String mode) {
        if (mode == null) {
            return false;
        }
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case VANILLA, LOSSLESS, BASELINE, UNIFIED, AGGRESSIVE, EXPERIMENTAL -> true;
            default -> false;
        };
    }

    public static boolean isVanilla(final String mode) {
        return mode != null && mode.toLowerCase(Locale.ROOT).equals(VANILLA);
    }

    /**
     * For {@code mode: vanilla}: clears the capability matrix (all features resolved off) and flips
     * every boolean performance flag in the loaded configuration back to {@code false}.
     *
     * @return number of configuration flags that were changed from {@code true} to {@code false}
     */
    public static int forceVanillaPath(final GlobalConfiguration.Agc agc) {
        AgcCapabilityMatrix.clearRuntimeOverrides();
        for (final AgcCapabilityMatrix.Feature feature : AgcCapabilityMatrix.Feature.values()) {
            AgcCapabilityMatrix.setRuntimeOverride(feature, Boolean.FALSE);
        }

        int disabled = 0;
        disabled += forceBooleansFalse(agc, 0);
        disabled += forceBooleansFalse(agc.performance, 0);
        disabled += neutraliseThrottles(agc.performance);

        LOGGER.info("AGC mode=vanilla: {} performance flags forced off for this run (exact Paper path, no AGC fast paths)", disabled);
        return disabled;
    }

    private static int forceBooleansFalse(final Object target, final int depth) {
        if (target == null || depth > 2) {
            return 0;
        }
        int disabled = 0;
        for (final Field field : target.getClass().getFields()) {
            if (!Modifier.isPublic(field.getModifiers()) || field.getType() != boolean.class) {
                continue;
            }
            try {
                if (field.getBoolean(target)) {
                    field.setBoolean(target, false);
                    disabled++;
                }
            } catch (final Throwable ignored) {
                // A field we cannot write is a field we must not pretend to have disabled.
            }
        }
        return disabled;
    }

    /**
     * Restores the numeric throttles that have no "off" boolean to their Paper-equivalent values
     * (1 = evaluate every tick, 0 = no artificial cap or margin).
     */
    private static int neutraliseThrottles(final GlobalConfiguration.Agc.Performance performance) {
        int changed = 0;
        changed += setInt(performance, "trackerUpdateThrottleTicks", 1, 1);
        changed += setInt(performance, "trackerIdleSkipRefreshTicks", 1, 1);
        changed += setInt(performance, "parallelWorldTickThreads", 1, 1);
        changed += setInt(performance, "noTickViewDistanceMargin", 0, 0);
        return changed;
    }

    private static int setInt(final Object target, final String fieldName, final int expectedMinimum, final int neutralValue) {
        if (target == null) {
            return 0;
        }
        try {
            final Field field = target.getClass().getField(fieldName);
            if (field.getType() != int.class) {
                return 0;
            }
            final int current = field.getInt(target);
            if (current == neutralValue) {
                return 0;
            }
            if (current >= expectedMinimum) {
                field.setInt(target, neutralValue);
                return 1;
            }
        } catch (final Throwable ignored) {
            // Unknown field: nothing to neutralise.
        }
        return 0;
    }
}
