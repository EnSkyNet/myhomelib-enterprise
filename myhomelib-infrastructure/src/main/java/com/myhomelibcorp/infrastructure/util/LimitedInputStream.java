package com.myhomelibcorp.infrastructure.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream з обмеженням максимальної кількості байтів, які можна прочитати.
 * Кидає IOException при перевищенні ліміту.
 */
public class LimitedInputStream extends FilterInputStream {

    private final long maxSize;
    private long totalRead = 0;

    public LimitedInputStream(InputStream in, long maxSize) {
        super(in);
        this.maxSize = maxSize;
    }

    @Override
    public int read() throws IOException {
        int result = super.read();
        if (result != -1) {
            totalRead++;
            checkLimit();
        }
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int bytesRead = super.read(b, off, len);
        if (bytesRead > 0) {
            totalRead += bytesRead;
            checkLimit();
        }
        return bytesRead;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        totalRead += skipped;
        checkLimit();
        return skipped;
    }

    private void checkLimit() throws IOException {
        if (totalRead > maxSize) {
            throw new IOException("Maximum size limit exceeded: " + maxSize + " bytes");
        }
    }

    public long getTotalRead() {
        return totalRead;
    }
}