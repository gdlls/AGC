package net.minecraft.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.World;

/**
 * Multi-world safety policy for AGC.
 * <p>
 * The policy separates safe empty-world packet hibernation from risky tick
 * skipping. It never declares a world's block/entity tick skippable unless an
 * integration supplies stronger evidence, because scheduled ticks, redstone and
 * farms must keep their Paper timing by default.
 */
public final class AGCMultiWorldPolicy {
    public static final AGCMultiWorldPolicy INSTANCE = new AGCMultiWorldPolicy();

    private AGCMultiWorldPolicy() {
    }

    public boolean canHibernatePackets(final World world) {
        return world != null && world.getPlayers().isEmpty();
    }

    public boolean canSkipWorldTick(final World world) {
        // Default false: loaded chunks may contain redstone, block entities,
        // random ticks or plugin-driven scheduled work even when no players are
        // present. AGC only hibernates avoidable packet/debug fanout by default.
        return false;
    }

    public boolean canUseParallelWorldTick(final List<? extends World> worlds) {
        if (worlds == null || worlds.size() < 2) {
            return false;
        }
        return AGCCompatibilityBridge.shouldEnableExperimentalParallelWorldTick()
            && AGCPerformanceGovernor.INSTANCE.isFeatureAllowed(AGCPerformanceGovernor.Feature.PARALLEL_WORLD_TICK)
            && !AGCThreadTranslator.INSTANCE.isBacklogged();
    }

    public List<String> hibernationReport(final List<? extends World> worlds) {
        if (worlds == null || worlds.isEmpty()) {
            return Collections.emptyList();
        }
        final ArrayList<String> report = new ArrayList<>(worlds.size());
        for (final World world : worlds) {
            if (world == null) {
                continue;
            }
            report.add(world.getName() + ": packetHibernate=" + this.canHibernatePackets(world) + ", skipTick=" + this.canSkipWorldTick(world));
        }
        return report;
    }
}
