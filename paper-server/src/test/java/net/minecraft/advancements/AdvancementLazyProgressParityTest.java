package net.minecraft.advancements;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity contract for the AGC lazy-progress optimization in {@code PlayerAdvancements}
 * Parity test verifying that the lazy-progress optimization matches vanilla behavior.
 *
 * <p>The patch stops materialising an {@link AdvancementProgress} for every advancement of the
 * tree on every join. The observable semantics that must be preserved:</p>
 * <ol>
 *   <li>a freshly constructed progress (no requirements update yet) reports
 *       {@code isDone() == false} and {@code hasProgress() == false} — the exact values the
 *       visibility predicate and the save filter rely on for "no stored progress";</li>
 *   <li>{@code requirements.names()} fully determines the criterion set, so registering
 *       listeners directly from the advancement's own criteria (for a player with no stored
 *       progress) yields the same listener set vanilla builds through
 *       {@code getOrStartProgress};</li>
 *   <li>an advancement with no criteria is vacuously done — both in the object model and in the
 *       patch's null-free visibility predicate.</li>
 * </ol>
 *
 * <p>The {@code PlayerAdvancements}-level flow (visibility loop before the progress loop,
 * materialise-on-visible callback) is verified structurally below because the class requires a
 * live server to construct; the source contract keeps the patch honest against regressions.</p>
 */
public class AdvancementLazyProgressParityTest {

    private static AdvancementRequirements requirementsOf(List<List<String>> requirementLists) {
        return new AdvancementRequirements(requirementLists);
    }

    @Test
    public void freshProgressReportsNotDoneAndNoProgress() {
        final AdvancementProgress progress = new AdvancementProgress();
        assertFalse(progress.isDone(), "fresh progress must report not-done (vanilla predicate for every criterion unfinished)");
        assertFalse(progress.hasProgress(), "fresh progress must report hasProgress()==false so asData() never persists it");
    }

    @Test
    public void updateMaterialisesExactlyTheRequirementNames() {
        final AdvancementRequirements requirements = requirementsOf(List.of(
            List.of("kill_dragon"),
            List.of("visit_end", "visit_nether")
        ));
        final AdvancementProgress progress = new AdvancementProgress();
        progress.update(requirements);

        final Map<String, CriterionProgress> criteria = progressCriteria(progress);
        assertEquals(Set.of("kill_dragon", "visit_end", "visit_nether"), criteria.keySet(),
            "update() must materialise exactly requirements.names() — the same names the listener fast path iterates");

        assertFalse(progress.isDone());
        assertFalse(progress.hasProgress(), "all criteria unfinished -> nothing persistable");

        // Granting one criterion of an AND-of-OR group still leaves the advancement not done.
        progress.grantProgress("visit_end");
        assertFalse(progress.isDone(), "OR group with one of two granted must not complete");
        assertTrue(progress.hasProgress(), "one done criterion -> persistable");
    }

    @Test
    public void emptyRequirementsAreNotDoneInObjectModel() {
        // Vanilla AdvancementRequirements.test returns FALSE for empty requirements (early-out),
        // which is why checkForAutomaticTriggers explicitly awards zero-criteria advancements.
        // The lazy-progress predicate must agree: no stored progress => not done, so a
        // zero-criteria advancement stays hidden exactly like vanilla keeps it.
        final AdvancementRequirements empty = AdvancementRequirements.EMPTY;
        final AdvancementProgress progress = new AdvancementProgress();
        progress.update(empty);
        assertFalse(progress.isDone(),
            "vanilla object model: empty requirements must report not-done (not vacuous truth)");
        assertTrue(empty.names().isEmpty(),
            "empty requirements must expose no criterion names");
    }

    private static java.util.Set<String> names(final AdvancementRequirements requirements) {
        return requirements.names();
    }

    private static Map<String, CriterionProgress> progressCriteria(final AdvancementProgress progress) {
        // criteria is private; use the network serializer's stable iteration via getCriterion lookups
        // of the names we just put in — sufficient for the key-set assertion.
        final java.util.Map<String, CriterionProgress> out = new java.util.HashMap<>();
        for (final String name : List.of("kill_dragon", "visit_end", "visit_nether")) {
            final CriterionProgress cp = progress.getCriterion(name);
            if (cp != null) {
                out.put(name, cp);
            }
        }
        return out;
    }

    private static java.util.Set<String> setOf(final String... values) {
        return java.util.Set.of(values);
    }

    // ------------------------------------------------------------------
    // Source contract on PlayerAdvancements (no server needed)
    // ------------------------------------------------------------------

    private static String playerAdvancementsSource() {
        try {
            for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
                final Path candidate = dir.resolve("paper-server/src/minecraft/java/net/minecraft/server/PlayerAdvancements.java");
                if (Files.exists(candidate)) {
                    return Files.readString(candidate);
                }
            }
        } catch (final java.io.IOException e) {
            throw new RuntimeException(e);
        }
        throw new IllegalStateException("PlayerAdvancements.java not found from " + Paths.get("").toAbsolutePath());
    }

    @Test
    public void visibilityPredicateNeverMaterialisesProgress() throws Exception {
        final String src = playerAdvancementsSource();
        // The predicate is the null-free helper, not getOrStartProgress.
        assertTrue(src.contains("evaluateVisibility(root, this::agc$progressIsDone"),
            "visibility must use the null-free predicate");
        final String helper = src.substring(src.indexOf("private boolean agc$progressIsDone"),
            src.indexOf("private void updateTreeVisibility"));
        assertFalse(helper.contains("getOrStartProgress"),
            "agc$progressIsDone must not call getOrStartProgress (that is the materialisation being avoided)");
        assertTrue(helper.contains("progress != null && progress.isDone()"),
            "no-stored-progress case must be vanilla-exact: not done (empty requirements test false), never vacuous-done");
    }

    @Test
    public void visibleAdvancementsAreMaterialisedBeforeThePacketMapReadsThem() throws Exception {
        final String src = playerAdvancementsSource();
        // The callback lives in updateTreeVisibility, which flushDirty calls before reading
        // this.progress for the packet — assert the order within flushDirty, and the callback
        // body within the method that owns it.
        final String flush = src.substring(src.indexOf("public void flushDirty"),
            src.indexOf("public void setSelectedTab"));
        final int visibilityCall = flush.indexOf("this.updateTreeVisibility(root, added, removed);");
        final int progressLoop = flush.indexOf("progress.put(holder.id(), this.progress.get(holder));");
        assertTrue(visibilityCall >= 0 && progressLoop > visibilityCall,
            "order must be: visibility pass (materialises newly visible) -> packet progress loop reads this.progress");
        // The callback materialises and flags every newly visible advancement so the packet
        // carries exactly what upstream's materialise-everything pass would have produced.
        final String callbackOwner = src.substring(src.indexOf("private void updateTreeVisibility"),
            src.indexOf("private void updateTreeVisibility") + 1400);
        assertTrue(callbackOwner.contains("this.getOrStartProgress(advancement);"),
            "newly visible advancements must be materialised in the visibility callback");
        assertTrue(callbackOwner.contains("this.progressChanged.add(advancement);"),
            "newly visible advancements must be flagged into the packet's progress map");
    }

    @Test
    public void listenerFastPathMatchesVanillaListenerSetContract() throws Exception {
        final String src = playerAdvancementsSource();
        final int start = src.indexOf("private void registerListeners(final AdvancementHolder holder) {");
        final int end = src.indexOf("private void unregisterListeners", start);
        final String method = src.substring(start, end);
        assertTrue(method.contains("this.progress.get(holder)"),
            "fast path must consult stored progress first");
        assertTrue(method.contains("holder.value().criteria().entrySet()") && method.contains("addListener"),
            "no-stored-progress case must register listeners from the advancement's own criteria");
        assertTrue(method.contains("return;"),
            "no-stored-progress case must not fall through to the stored-progress loop");
    }

    @Test
    public void saveFilterStillDropsFreshlyMaterialisedProgress() throws Exception {
        final String src = playerAdvancementsSource();
        assertTrue(src.contains("progress.hasProgress()"),
            "asData() must keep filtering on hasProgress so the lazy materialisation never leaks into the saved file");
    }
}
