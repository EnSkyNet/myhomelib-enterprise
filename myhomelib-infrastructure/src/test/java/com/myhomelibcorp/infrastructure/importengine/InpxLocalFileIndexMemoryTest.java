package com.myhomelibcorp.infrastructure.importengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InpxLocalFileIndexMemoryTest {
    @TempDir Path root;

    @Test
    void exactLimitKeepsCompleteFileIndex() throws Exception {
        Path one = Files.writeString(root.resolve("one.fb2"), "1");
        Path two = Files.writeString(root.resolve("two.fb2"), "2");
        Set<Path> indexed = InpxImportPipeline.indexExistingLocalFiles(root, null, 2);
        assertNotNull(indexed);
        assertEquals(Set.of(one.toAbsolutePath().normalize(), two.toAbsolutePath().normalize()), indexed);
    }

    @Test
    void overLimitFallsBackInsteadOfPublishingAnIncompleteIndex() throws Exception {
        Files.writeString(root.resolve("one.fb2"), "1");
        Files.writeString(root.resolve("two.fb2"), "2");
        Files.writeString(root.resolve("three.fb2"), "3");
        assertNull(InpxImportPipeline.indexExistingLocalFiles(root, null, 2));
    }

    @Test
    void overflowAtOnePathFallsBack() throws Exception {
        Files.writeString(root.resolve("one.fb2"), "1");
        Files.writeString(root.resolve("two.fb2"), "2");
        assertNull(InpxImportPipeline.indexExistingLocalFiles(root, null, 1));
    }

    @Test
    void cancelledScanReturnsEmptyWithoutRetainingPartialResults() throws Exception {
        Files.writeString(root.resolve("one.fb2"), "1");
        assertEquals(Set.of(), InpxImportPipeline.indexExistingLocalFiles(root, new AtomicBoolean(true), 2));
    }

    @Test
    void missingOrNullRootReturnsEmptyIndex() {
        assertEquals(Set.of(), InpxImportPipeline.indexExistingLocalFiles(root.resolve("missing"), null, 2));
        assertEquals(Set.of(), InpxImportPipeline.indexExistingLocalFiles(null, null, 2));
    }

    @Test
    void unicodeNamesRemainDiscoverable() throws Exception {
        Path book = Files.writeString(root.resolve("Книга-2026.fb2"), "text");
        Set<Path> indexed = InpxImportPipeline.indexExistingLocalFiles(root, null, 2);
        assertNotNull(indexed);
        assertTrue(indexed.contains(book.toAbsolutePath().normalize()));
    }

    @Test
    void invalidLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                InpxImportPipeline.indexExistingLocalFiles(root, null, 0));
    }

    @Test
    void boundedCacheEvictsOldEntriesWithoutGrowth() {
        Map<String, Boolean> cache = InpxImportPipeline.newBoundedLocalFileProbeCache();
        for (int i = 0; i < InpxImportPipeline.MAX_LOCAL_FILE_PROBE_CACHE_ENTRIES + 64; i++) {
            cache.put("archive-" + i, i % 2 == 0);
        }
        assertEquals(InpxImportPipeline.MAX_LOCAL_FILE_PROBE_CACHE_ENTRIES, cache.size());
        assertFalse(cache.containsKey("archive-0"));
        assertTrue(cache.containsKey("archive-" + (InpxImportPipeline.MAX_LOCAL_FILE_PROBE_CACHE_ENTRIES + 63)));
    }

    @Test
    void cacheReadsRefreshLeastRecentlyUsedOrder() {
        Map<String, Boolean> cache = InpxImportPipeline.newBoundedLocalFileProbeCache();
        int limit = InpxImportPipeline.MAX_LOCAL_FILE_PROBE_CACHE_ENTRIES;
        for (int i = 0; i < limit; i++) cache.put("archive-" + i, false);
        assertEquals(Boolean.FALSE, cache.get("archive-0"));
        cache.put("archive-new", true);
        assertTrue(cache.containsKey("archive-0"));
        assertFalse(cache.containsKey("archive-1"));
    }

    @Test
    void evictedProbeIsRecomputedExactlyWhenNeeded() {
        Map<String, Boolean> cache = InpxImportPipeline.newBoundedLocalFileProbeCache();
        int limit = InpxImportPipeline.MAX_LOCAL_FILE_PROBE_CACHE_ENTRIES;
        for (int i = 0; i <= limit; i++) cache.put("book-" + i, false);
        assertEquals(Boolean.TRUE, cache.computeIfAbsent("book-0", ignored -> true));
        assertEquals(limit, cache.size());
    }

    @Test
    void independentImportsDoNotShareCacheState() {
        Map<String, Boolean> first = InpxImportPipeline.newBoundedLocalFileProbeCache();
        Map<String, Boolean> second = InpxImportPipeline.newBoundedLocalFileProbeCache();
        first.put("a", true);
        assertFalse(second.containsKey("a"));
    }
}
