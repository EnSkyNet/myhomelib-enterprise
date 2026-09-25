package com.myhomelibcorp.application.conversion;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Input boundary shared by all book-conversion entry points.
 *
 * <p>The wrapper prevents a provider from consuming more bytes than the application job allows
 * and observes cancellation before each blocking read. The one-byte probe at the exact limit is
 * intentional: it distinguishes an input whose size is exactly {@code maxBytes} from one that
 * contains additional data.</p>
 */
public final class CancellableBoundedInputStream extends FilterInputStream {
    private final long maxBytes;
    private final BooleanSupplier cancelled;
    private long count;

    public CancellableBoundedInputStream(InputStream delegate, long maxBytes, BooleanSupplier cancelled) {
        super(java.util.Objects.requireNonNull(delegate, "delegate"));
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
        this.cancelled = cancelled == null ? () -> false : cancelled;
    }

    @Override
    public int read() throws IOException {
        checkCancelled();
        if (count >= maxBytes) {
            int extra = super.read();
            if (extra < 0) return -1;
            count++;
            throw limitExceeded();
        }
        int value = super.read();
        if (value >= 0) count++;
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) return 0;
        checkCancelled();
        long remaining = maxBytes - count;
        if (remaining <= 0) {
            int extra = super.read();
            if (extra < 0) return -1;
            count++;
            throw limitExceeded();
        }
        int boundedLength = (int) Math.min((long) length, remaining);
        int read = super.read(buffer, offset, boundedLength);
        if (read > 0) count += read;
        return read;
    }


    @Override
    public long skip(long amount) throws IOException {
        if (amount <= 0) return 0L;
        checkCancelled();
        long remaining = maxBytes - count;
        if (remaining <= 0) {
            int extra = super.read();
            if (extra < 0) return 0L;
            count++;
            throw limitExceeded();
        }
        long skipped = super.skip(Math.min(amount, remaining));
        if (skipped > 0) count += skipped;
        return skipped;
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
    }

    private IOException limitExceeded() {
        return new IOException("Conversion input exceeds limit: " + maxBytes + " bytes");
    }
}
