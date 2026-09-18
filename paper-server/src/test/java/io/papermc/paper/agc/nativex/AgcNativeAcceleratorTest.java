package io.papermc.paper.agc.nativex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgcNativeAcceleratorTest {

    @BeforeEach
    public void setup() {
        AgcNativeAccelerator.get().clear();
    }

    @Test
    public void testMemoryCopyAndFill() {
        AgcNativeAccelerator nativex = AgcNativeAccelerator.get();

        byte[] src = new byte[256];
        byte[] dest = new byte[256];
        for (int i = 0; i < 256; i++) {
            src[i] = (byte) (i & 0xFF);
        }

        nativex.copyMemory(src, 0, dest, 0, 256);
        for (int i = 0; i < 256; i++) {
            assertEquals(src[i], dest[i]);
        }

        nativex.fillMemory(dest, 0, 256, (byte) 7);
        for (int i = 0; i < 256; i++) {
            assertEquals((byte) 7, dest[i]);
        }

        assertEquals(2, nativex.metrics().nativeOperations());
        assertEquals(512, nativex.metrics().bytesProcessed());
    }

    @Test
    public void testFastNoise2D() {
        AgcNativeAccelerator nativex = AgcNativeAccelerator.get();

        double val1 = nativex.fastNoise2D(10.5, 20.3);
        double val2 = nativex.fastNoise2D(10.5, 20.3);
        double val3 = nativex.fastNoise2D(50.0, 99.0);

        assertEquals(val1, val2, 1.0e-9);
        assertTrue(val1 >= -2.0 && val1 <= 2.0);
        assertTrue(val3 >= -2.0 && val3 <= 2.0);
    }
}
