package io.papermc.paper.agc.io;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AgcRegionFileDirectIoEngineTest {

    @Test
    public void testDirectIoEngine() throws Exception {
        AgcRegionFileDirectIoEngine engine = AgcRegionFileDirectIoEngine.get();
        Path tempFile = Files.createTempFile("agc_test_region", ".mca");
        tempFile.toFile().deleteOnExit();

        ByteBuffer writeBuffer = ByteBuffer.allocate(1024);
        writeBuffer.put("AGC DIRECT IO TEST".getBytes());
        writeBuffer.flip();

        engine.writeDirect(tempFile, 0, writeBuffer);
        
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        engine.readDirect(tempFile, 0, readBuffer);
        readBuffer.flip();
        
        byte[] bytes = new byte[18];
        readBuffer.get(bytes);
        assertEquals("AGC DIRECT IO TEST", new String(bytes));
        
        assertTrue(engine.getBytesWrittenDirectly() >= 18);
        assertTrue(engine.getBytesReadDirectly() >= 18);
    }
}
