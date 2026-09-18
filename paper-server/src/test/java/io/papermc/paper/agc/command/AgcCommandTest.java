package io.papermc.paper.agc.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcCommandTest {

    @BeforeEach
    public void setup() {
        AgcCommand.get().clear();
    }

    @Test
    public void testHelpCommand() {
        AgcCommand cmd = AgcCommand.get();
        String out = cmd.execute(new String[]{"help"});
        assertNotNull(out);
        assertTrue(out.contains("AGC Administrative Commands"));
        assertEquals(1, cmd.getCommandsExecuted());
    }

    @Test
    public void testStatusAndWorldsCommand() {
        AgcCommand cmd = AgcCommand.get();
        String status = cmd.execute(new String[]{"status"});
        assertNotNull(status);
        assertTrue(status.contains("AGC System Status"));

        String worlds = cmd.execute(new String[]{"worlds"});
        assertNotNull(worlds);
        assertTrue(worlds.contains("AGC Worlds & Hibernation"));
    }

    @Test
    public void testPresetCommand() {
        AgcCommand cmd = AgcCommand.get();
        String out = cmd.execute(new String[]{"preset", "massive_enterprise"});
        assertNotNull(out);
        assertTrue(out.contains("Successfully applied server scale preset"));
    }
}
