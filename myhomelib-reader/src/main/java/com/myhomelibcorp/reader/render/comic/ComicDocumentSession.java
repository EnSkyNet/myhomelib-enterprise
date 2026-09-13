package com.myhomelibcorp.reader.render.comic;

import com.myhomelibcorp.shared.comic.ComicPageNameSupport;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy CBZ/CBR image session. Opening a comic only enumerates safe image names;
 * archive members are decoded on demand and cached under a bounded raster budget.
 */
public final class ComicDocumentSession implements AutoCloseable {
    static final long MAX_PAGE_BYTES = 64L * 1024L * 1024L;
    static final long MAX_PAGE_PIXELS = 20_000_000L;
    static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;

    private final ComicPageSource source;
    private final List<String> pages;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object cacheLock = new Object();
    private final LinkedHashMap<RenderKey, ComicRenderedPage> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes;

    private ComicDocumentSession(ComicPageSource source, List<String> pages) {
        this.source = source;
        this.pages = pages;
    }

    public static ComicDocumentSession open(ComicPageSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        checkInterrupted();
        List<String> pages = ComicPageNameSupport.sortPages(source.listPageEntries());
        if (pages.isEmpty()) throw new IOException("Comic archive contains no supported image pages");
        return new ComicDocumentSession(source, pages);
    }

    public int pageCount() {
        return pages.size();
    }

    public String pageName(int pageIndex) {
        return pages.get(requirePage(pageIndex));
    }

    /**
     * Decodes a page for the requested viewport. ImageIO source subsampling keeps thumbnails
     * and normal screen rendering bounded instead of always decoding full source resolution.
     */
    public ComicRenderedPage render(int pageIndex, int targetMaxWidth, int targetMaxHeight)
            throws IOException, InterruptedException {
        ensureOpen();
        checkInterrupted();
        int page = requirePage(pageIndex);
        int bucket = renderBucket(targetMaxWidth, targetMaxHeight);
        RenderKey key = new RenderKey(page, bucket);
        synchronized (cacheLock) {
            ComicRenderedPage cached = cache.get(key);
            if (cached != null) return cached;
        }

        String entry = pages.get(page);
        ComicRenderedPage rendered;
        try (InputStream raw = source.openPage(entry)) {
            if (raw == null) throw new IOException("Comic page is unavailable: " + entry);
            try (InputStream bounded = new BoundedInputStream(raw, MAX_PAGE_BYTES);
                 ImageInputStream imageInput = new MemoryCacheImageInputStream(bounded)) {
                rendered = decode(page, imageInput, bucket);
            }
        }
        checkInterrupted();
        remember(key, rendered);
        return rendered;
    }

    private static ComicRenderedPage decode(int pageIndex, ImageInputStream input, int bucket) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw new IOException("Unsupported or corrupt comic image");
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            long pixels = (long) width * height;
            if (width <= 0 || height <= 0 || pixels > MAX_PAGE_PIXELS) {
                throw new IOException("Comic page dimensions exceed the safety limit: " + width + "x" + height);
            }

            ImageReadParam param = reader.getDefaultReadParam();
            int sample = subsampling(width, height, bucket);
            if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0);
            BufferedImage image = reader.read(0, param);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IOException("Unable to decode comic page");
            }
            int[] argb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            ComicRenderedPage result = new ComicRenderedPage(pageIndex, image.getWidth(), image.getHeight(), argb);
            image.flush();
            return result;
        } finally {
            reader.dispose();
        }
    }

    private static int subsampling(int width, int height, int bucket) {
        if (bucket >= 8192) return 1;
        int largest = Math.max(width, height);
        int sample = 1;
        while (sample < 16 && largest / (sample * 2) >= bucket) sample *= 2;
        return sample;
    }

    private static int renderBucket(int targetMaxWidth, int targetMaxHeight) {
        int requested = Math.max(targetMaxWidth, targetMaxHeight);
        if (requested <= 0) return 8192;
        int bucket = 256;
        while (bucket < requested && bucket < 8192) bucket <<= 1;
        return Math.min(bucket, 8192);
    }

    private void remember(RenderKey key, ComicRenderedPage rendered) {
        long bytes = rendered.estimatedBytes();
        if (bytes > MAX_CACHE_BYTES) return;
        synchronized (cacheLock) {
            ComicRenderedPage previous = cache.put(key, rendered);
            if (previous != null) cacheBytes -= previous.estimatedBytes();
            cacheBytes += bytes;
            Iterator<Map.Entry<RenderKey, ComicRenderedPage>> iterator = cache.entrySet().iterator();
            while (cacheBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
                Map.Entry<RenderKey, ComicRenderedPage> eldest = iterator.next();
                cacheBytes -= eldest.getValue().estimatedBytes();
                iterator.remove();
            }
        }
    }

    private int requirePage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pages.size()) throw new IndexOutOfBoundsException("Comic page " + pageIndex);
        return pageIndex;
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("Comic session is closed");
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Comic operation cancelled");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (cacheLock) {
            cache.clear();
            cacheBytes = 0;
        }
    }

    private record RenderKey(int pageIndex, int bucket) { }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long total;

        private BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) add(1);
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) add(read);
            return read;
        }

        private void add(long count) throws IOException {
            total += count;
            if (total > limit) throw new IOException("Comic page exceeds the compressed-byte safety limit");
        }
    }
}
