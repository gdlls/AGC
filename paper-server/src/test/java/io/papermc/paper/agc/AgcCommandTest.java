package io.papermc.paper.agc;

import io.papermc.paper.command.AgcCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgcCommand}.
 */
class AgcCommandTest {

    @Test
    void commandMetadataIsCorrect() {
        final AgcCommand cmd = new AgcCommand("agc");
        assertEquals("agc", cmd.getName());
        assertEquals(AgcCommand.BASE_PERM, cmd.getPermission());
        assertNotNull(cmd.getDescription());
        assertTrue(cmd.getDescription().contains("AGC"));
        assertNotNull(cmd.getUsage());
        assertTrue(cmd.getUsage().contains("/agc"));
    }

    @Test
    void modeEnumContainsAllTargetModes() {
        final AgcCapabilityMatrix.Mode vanilla = AgcCapabilityMatrix.Mode.valueOf("VANILLA");
        final AgcCapabilityMatrix.Mode baseline = AgcCapabilityMatrix.Mode.valueOf("AGC_BASELINE");
        final AgcCapabilityMatrix.Mode aggressive = AgcCapabilityMatrix.Mode.valueOf("AGC_AGGRESSIVE");

        assertNotNull(vanilla);
        assertNotNull(baseline);
        assertNotNull(aggressive);
    }
}
