package io.papermc.paper.agc.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * AGC — High Performance Chunk Compression & Buffer Pool (Krypton / C2ME port).
 *
 * <p>Recycles {@link Deflater}, {@link Inflater}, and byte array buffers across chunk
 * serialization passes, avoiding high GC pressure in chunk save/load loops.</p>
 */
public final class AgcChunkCompressionPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgcChunkCompressionPool.class);
    private static final AgcChunkCompressionPool INSTANCE = new AgcChunkCompressionPool();

    private static final ThreadLocal<Deflater> DEFLATER_POOL = ThreadLocal.withInitial(() -> new Deflater(Deflater.DEFAULT_COMPRESSION));
    private static final ThreadLocal<Inflater> INFLATER_POOL = ThreadLocal.withInitial(Inflater::new);
    private static final ThreadLocal<byte[]> BUFFER_POOL_8K = ThreadLocal.withInitial(() -> new byte[8192]);
    private static final ThreadLocal<ReusableByteArrayOutputStream> STREAM_POOL = ThreadLocal.withInitial(ReusableByteArrayOutputStream::new);

    public static AgcChunkCompressionPool get() {
        return INSTANCE;
    }

    private AgcChunkCompressionPool() {}

    public Deflater acquireDeflater(final int level) {
        final Deflater deflater = DEFLATER_POOL.get();
        deflater.reset();
        deflater.setLevel(level);
        return deflater;
    }

    public Inflater acquireInflater() {
        final Inflater inflater = INFLATER_POOL.get();
        inflater.reset();
        return inflater;
    }

    public byte[] acquire8kBuffer() {
        return BUFFER_POOL_8K.get();
    }

    public ReusableByteArrayOutputStream acquireStream() {
        final ReusableByteArrayOutputStream stream = STREAM_POOL.get();
        stream.reset();
        return stream;
    }

    public static final class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        public ReusableByteArrayOutputStream() {
            super(8192);
        }

        public byte[] getRawBuffer() {
            return this.buf;
        }
    }
}
