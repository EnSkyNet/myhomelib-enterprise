package com.myhomelibcorp.reader.render.pdf;

import com.myhomelibcorp.reader.api.BookSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Open PDF document with bounded LRU raster cache. Parsing/rendering methods are intentionally UI-neutral
 * and are expected to run on a background executor.
 */
public final class PdfDocumentSession implements AutoCloseable {
    private static final long DEFAULT_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final int MIN_DPI = 36;
    private static final int MAX_DPI = 288;
    private static final long MAX_RASTER_PIXELS = 12_000_000L;
    private static final int COPY_BUFFER = 64 * 1024;
    private static final int MAX_SEARCH_QUERY_CHARS = 256;
    private static final int MAX_SEARCH_RESULTS = 200;
    private static final int SEARCH_SNIPPET_CONTEXT = 72;
    private static final int MAX_OUTLINE_ENTRIES = 5_000;
    private static final int MAX_OUTLINE_DEPTH = 32;

    private final PDDocument document;
    private final PDFRenderer renderer;
    private final int pageCount;
    private final List<PdfPageSize> pageSizes;
    private final List<PdfOutlineEntry> outlineEntries;
    private final String sourceId;
    private final String sourceName;
    private final Path localCopy;
    private final long maxCacheBytes;
    private final LinkedHashMap<CacheKey, PdfRenderedPage> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object operationLock = new Object();

    private PdfDocumentSession(PDDocument document, List<PdfPageSize> pageSizes, List<PdfOutlineEntry> outlineEntries,
                               String sourceId, String sourceName, Path localCopy, long maxCacheBytes) {
        this.document = document;
        this.renderer = new PDFRenderer(document);
        this.renderer.setSubsamplingAllowed(true);
        this.pageCount = document.getNumberOfPages();
        this.pageSizes = List.copyOf(pageSizes);
        this.outlineEntries = List.copyOf(outlineEntries);
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.localCopy = localCopy;
        this.maxCacheBytes = Math.max(8L * 1024L * 1024L, maxCacheBytes);
    }

    public static PdfDocumentSession open(BookSource source) throws IOException, InterruptedException {
        return open(source, "");
    }

    /** Opens a PDF with an optional user-supplied password. The password is never persisted by the Reader. */
    public static PdfDocumentSession open(BookSource source, String password) throws IOException, InterruptedException {
        if (source == null) throw new IllegalArgumentException("source is required");
        checkInterrupted();
        Path copy = Files.createTempFile("myhomelib-pdf-", ".pdf");
        PDDocument doc = null;
        try {
            copySource(source, copy);
            checkInterrupted();
            doc = Loader.loadPDF(copy.toFile(), password == null ? "" : password, IOUtils.createTempFileOnlyStreamCache());
            checkInterrupted();
            if (doc.getNumberOfPages() <= 0) throw new IOException("PDF contains no pages");
            List<PdfPageSize> pageSizes = readPageSizes(doc);
            List<PdfOutlineEntry> outlineEntries = readOutlineBestEffort(doc);
            return new PdfDocumentSession(doc, pageSizes, outlineEntries, source.id(), source.name(), copy, DEFAULT_CACHE_BYTES);
        } catch (InvalidPasswordException encrypted) {
            PdfPasswordRequiredException failure = new PdfPasswordRequiredException("PDF password is required or incorrect", encrypted);
            closeAndDeleteAfterOpenFailure(doc, copy, failure);
            throw failure;
        } catch (Throwable error) {
            closeAndDeleteAfterOpenFailure(doc, copy, error);
            if (error instanceof InterruptedException interrupted) throw interrupted;
            if (error instanceof IOException io) throw io;
            if (error instanceof Error fatal) throw fatal;
            throw new IOException("Unable to open PDF: " + error.getMessage(), error);
        }
    }

    private static void closeAndDeleteAfterOpenFailure(PDDocument document, Path copy, Throwable primary) {
        if (document != null) {
            try { document.close(); }
            catch (IOException closeError) { primary.addSuppressed(closeError); }
        }
        try { Files.deleteIfExists(copy); }
        catch (IOException deleteError) { primary.addSuppressed(deleteError); }
    }


    private static List<PdfPageSize> readPageSizes(PDDocument document) throws InterruptedException {
        List<PdfPageSize> sizes = new ArrayList<>(document.getNumberOfPages());
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            checkInterrupted();
            PDPage page = document.getPage(index);
            PDRectangle box = page.getCropBox();
            if (box == null) box = page.getMediaBox();
            double width = box == null ? 612.0 : box.getWidth();
            double height = box == null ? 792.0 : box.getHeight();
            int rotation = Math.floorMod(page.getRotation(), 360);
            if (rotation == 90 || rotation == 270) {
                double tmp = width; width = height; height = tmp;
            }
            sizes.add(new PdfPageSize(width, height));
        }
        return List.copyOf(sizes);
    }

    private static List<PdfOutlineEntry> readOutlineBestEffort(PDDocument document) throws InterruptedException {
        try {
            return readOutline(document);
        } catch (RuntimeException malformedOutline) {
            // Outline metadata is optional. A malformed outline must not block page reading/rendering.
            return List.of();
        }
    }

    private static List<PdfOutlineEntry> readOutline(PDDocument document) throws InterruptedException {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null || outline.getFirstChild() == null) return List.of();

        Map<COSDictionary, Integer> pageIndexes = new IdentityHashMap<>();
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            checkInterrupted();
            pageIndexes.put(document.getPage(i).getCOSObject(), i);
        }
        List<PdfOutlineEntry> result = new ArrayList<>();
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        appendOutlineSiblings(document, outline.getFirstChild(), 0, pageIndexes, visited, result);
        return List.copyOf(result);
    }

    private static void appendOutlineSiblings(PDDocument document, PDOutlineItem first, int level,
                                              Map<COSDictionary, Integer> pageIndexes,
                                              Set<COSDictionary> visited, List<PdfOutlineEntry> result)
            throws InterruptedException {
        if (first == null || level > MAX_OUTLINE_DEPTH || result.size() >= MAX_OUTLINE_ENTRIES) return;
        PDOutlineItem current = first;
        while (current != null && result.size() < MAX_OUTLINE_ENTRIES) {
            checkInterrupted();
            COSDictionary key = current.getCOSObject();
            if (!visited.add(key)) break;
            try {
                PDPage page = current.findDestinationPage(document);
                Integer pageIndex = page == null ? null : pageIndexes.get(page.getCOSObject());
                String title = current.getTitle();
                if (pageIndex != null && title != null && !title.isBlank()) {
                    result.add(new PdfOutlineEntry(title, pageIndex, level));
                }
            } catch (IOException ignored) {
                // A malformed outline node must not make an otherwise readable PDF fail to open.
            }
            if (level < MAX_OUTLINE_DEPTH && current.getFirstChild() != null) {
                appendOutlineSiblings(document, current.getFirstChild(), level + 1,
                        pageIndexes, visited, result);
            }
            current = current.getNextSibling();
        }
    }

    private static void copySource(BookSource source, Path copy) throws IOException, InterruptedException {
        try (InputStream in = source.openStream(); OutputStream out = Files.newOutputStream(copy)) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                checkInterrupted();
                if (read > 0) out.write(buffer, 0, read);
            }
        }
    }

    public int pageCount() { return pageCount; }
    public String sourceId() { return sourceId; }
    public String sourceName() { return sourceName; }

    public PdfPageSize pageSize(int pageIndex) {
        ensurePageIndex(pageIndex);
        return pageSizes.get(pageIndex);
    }

    public List<PdfOutlineEntry> outlineEntries() {
        return outlineEntries;
    }

    /**
     * Searches only the PDF text layer. Image-only/scanned pages remain readable but return an
     * outcome with {@code textLayerDetected=false}; no implicit OCR is attempted.
     */
    public PdfSearchOutcome searchText(String query, int maxResults) throws IOException, InterruptedException {
        String needle = query == null ? "" : query.strip();
        if (needle.isEmpty()) return new PdfSearchOutcome(List.of(), false);
        if (needle.length() > MAX_SEARCH_QUERY_CHARS) {
            throw new IllegalArgumentException("PDF search query is too long");
        }
        int limit = Math.max(1, Math.min(MAX_SEARCH_RESULTS, maxResults));
        synchronized (operationLock) {
            ensureOpen();
            checkInterrupted();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<PdfSearchResult> results = new ArrayList<>();
            boolean textLayerDetected = false;
            for (int page = 0; page < pageCount && results.size() < limit; page++) {
                checkInterrupted();
                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                String pageText = stripper.getText(document);
                checkInterrupted();
                if (pageText == null || pageText.isBlank()) continue;
                textLayerDetected = true;
                collectMatches(pageText, needle, page, limit, results);
            }
            return new PdfSearchOutcome(results, textLayerDetected);
        }
    }

    private static void collectMatches(String pageText, String query, int pageIndex, int limit,
                                       List<PdfSearchResult> results) {
        int from = 0;
        while (from <= pageText.length() - query.length() && results.size() < limit) {
            int match = indexOfIgnoreCase(pageText, query, from);
            if (match < 0) break;
            int end = match + query.length();
            results.add(new PdfSearchResult(pageIndex, match, end, snippet(pageText, match, end)));
            from = Math.max(end, match + 1);
        }
    }

    private static int indexOfIgnoreCase(String text, String query, int fromIndex) {
        int max = text.length() - query.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (text.regionMatches(true, i, query, 0, query.length())) return i;
        }
        return -1;
    }

    private static String snippet(String text, int start, int end) {
        int left = Math.max(0, start - SEARCH_SNIPPET_CONTEXT);
        int right = Math.min(text.length(), end + SEARCH_SNIPPET_CONTEXT);
        String compact = text.substring(left, right).replaceAll("\\s+", " ").strip();
        if (left > 0) compact = "…" + compact;
        if (right < text.length()) compact = compact + "…";
        return compact;
    }

    public PdfRenderedPage render(int pageIndex, double scale) throws IOException, InterruptedException {
        synchronized (operationLock) {
            ensureOpen();
            ensurePageIndex(pageIndex);
            checkInterrupted();
            int dpi = boundedDpi(pageSizes.get(pageIndex), normalizeDpi(scale));
            CacheKey key = new CacheKey(pageIndex, dpi);
            synchronized (cache) {
                PdfRenderedPage hit = cache.get(key);
                if (hit != null) return hit;
            }
            checkInterrupted();
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.ARGB);
            checkInterrupted();
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
            PdfRenderedPage rendered = new PdfRenderedPage(pageIndex, width, height, argb);
            synchronized (cache) {
                PdfRenderedPage previous = cache.put(key, rendered);
                if (previous != null) cacheBytes -= previous.estimatedBytes();
                cacheBytes += rendered.estimatedBytes();
                evictIfNeeded();
            }
            return rendered;
        }
    }

    public void clearCache() {
        synchronized (cache) {
            cache.clear();
            cacheBytes = 0;
        }
    }

    public long cachedBytes() {
        synchronized (cache) { return cacheBytes; }
    }

    private void evictIfNeeded() {
        var iterator = cache.entrySet().iterator();
        while (cacheBytes > maxCacheBytes && cache.size() > 1 && iterator.hasNext()) {
            Map.Entry<CacheKey, PdfRenderedPage> eldest = iterator.next();
            cacheBytes -= eldest.getValue().estimatedBytes();
            iterator.remove();
        }
    }

    private void ensurePageIndex(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageCount) throw new IndexOutOfBoundsException("PDF page " + pageIndex);
    }

    private static int boundedDpi(PdfPageSize page, int requestedDpi) throws IOException {
        double width = page.widthPoints();
        double height = page.heightPoints();
        double requestedPixels = (width / 72.0 * requestedDpi) * (height / 72.0 * requestedDpi);
        if (!Double.isFinite(requestedPixels) || requestedPixels <= 0) {
            throw new IOException("PDF page dimensions are invalid for rasterization");
        }
        if (requestedPixels <= MAX_RASTER_PIXELS) return requestedDpi;

        double ratio = Math.sqrt(MAX_RASTER_PIXELS / requestedPixels);
        int safeDpi = (int) Math.floor(requestedDpi * ratio);
        if (safeDpi < MIN_DPI) {
            double minPixels = (width / 72.0 * MIN_DPI) * (height / 72.0 * MIN_DPI);
            if (!Double.isFinite(minPixels) || minPixels > MAX_RASTER_PIXELS) {
                throw new IOException("PDF page exceeds the raster safety limit");
            }
            safeDpi = MIN_DPI;
        }
        return Math.min(requestedDpi, safeDpi);
    }

    private static int normalizeDpi(double scale) {
        double safe = Double.isFinite(scale) ? scale : 1.0;
        return (int) Math.max(MIN_DPI, Math.min(MAX_DPI, Math.round(72.0 * Math.max(0.5, Math.min(4.0, safe)))));
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("PDF session is closed");
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("PDF operation cancelled");
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        IOException failure = null;
        synchronized (operationLock) {
            clearCache();
            try {
                document.close();
            } catch (IOException error) {
                failure = error;
            }
        }
        try {
            Files.deleteIfExists(localCopy);
        } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private record CacheKey(int pageIndex, int dpi) { }
}
