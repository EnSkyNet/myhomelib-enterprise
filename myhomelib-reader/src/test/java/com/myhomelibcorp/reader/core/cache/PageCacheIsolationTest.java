package com.myhomelibcorp.reader.core.cache;

import com.myhomelibcorp.reader.api.BookMetadata;
import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.layout.TextLayoutEngine;
import com.myhomelibcorp.reader.model.PageLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageCacheIsolationTest {
    private static final PageDimensions DIMENSIONS = new PageDimensions(800, 600, 10, 10, 10, 10);

    private static ReaderDocument document(String id) {
        ReaderDocument document = mock(ReaderDocument.class);
        when(document.metadata()).thenReturn(new BookMetadata(
                id, "Book", List.of(), "uk", null, null, List.of(),
                "", "", "", null, 0L));
        return document;
    }

    private static ReaderDocument withoutMetadata() {
        return mock(ReaderDocument.class);
    }

    private static ReaderPosition at(long offset) {
        return new ReaderPosition(0, offset, 0, 0);
    }

    private static TextLayoutEngine countingLayout(AtomicInteger calls) {
        TextLayoutEngine layout = mock(TextLayoutEngine.class);
        when(layout.layoutPage(any(ReaderDocument.class), anyLong(), any(PageDimensions.class)))
                .thenAnswer(call -> PageLayout.builder()
                        .pageNumber(calls.incrementAndGet()).build());
        return layout;
    }

    @Test
    void cachesIdenticalRequestsWithoutRecomputing() {
        PageCache cache = new PageCache(4);
        AtomicInteger calls = new AtomicInteger();
        TextLayoutEngine layout = countingLayout(calls);
        ReaderDocument book = document("book-1");
        PageLayout first = cache.getOrCompute(book, at(1), DIMENSIONS, layout);
        assertThat(cache.getOrCompute(book, at(1), DIMENSIONS, layout)).isSameAs(first);
        assertThat(calls).hasValue(1);
    }

    @Test
    void emptyBookIdsDoNotShareCachedPages() {
        PageCache cache = new PageCache(4);
        AtomicInteger calls = new AtomicInteger();
        TextLayoutEngine layout = countingLayout(calls);
        ReaderDocument first = document("");
        ReaderDocument second = document("");
        assertThat(cache.getOrCompute(first, at(0), DIMENSIONS, layout))
                .isNotSameAs(cache.getOrCompute(second, at(0), DIMENSIONS, layout));
        assertThat(calls).hasValue(2);
    }

    @Test
    void absentMetadataDoesNotShareCachedPages() {
        PageCache cache = new PageCache(4);
        AtomicInteger calls = new AtomicInteger();
        TextLayoutEngine layout = countingLayout(calls);
        ReaderDocument first = withoutMetadata();
        ReaderDocument second = withoutMetadata();
        assertThat(cache.getOrCompute(first, at(0), DIMENSIONS, layout))
                .isNotSameAs(cache.getOrCompute(second, at(0), DIMENSIONS, layout));
        assertThat(calls).hasValue(2);
    }

    @Test
    void explicitDocumentIdEvictionRemovesAllItsPageOffsets() {
        PageCache cache = new PageCache(6);
        TextLayoutEngine layout = countingLayout(new AtomicInteger());
        ReaderDocument first = document("first");
        ReaderDocument second = document("second");
        cache.getOrCompute(first, at(0), DIMENSIONS, layout);
        cache.getOrCompute(first, at(10), DIMENSIONS, layout);
        cache.getOrCompute(second, at(0), DIMENSIONS, layout);
        cache.evict("first");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void documentEvictionRemovesOnlyMatchingAnonymousDocument() {
        PageCache cache = new PageCache(5);
        TextLayoutEngine layout = countingLayout(new AtomicInteger());
        ReaderDocument first = document("");
        ReaderDocument second = document("");
        cache.getOrCompute(first, at(0), DIMENSIONS, layout);
        cache.getOrCompute(second, at(0), DIMENSIONS, layout);
        cache.evictForDocument(first);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void explicitIdCannotAccidentallyEvictAnAnonymousDocument() {
        PageCache cache = new PageCache(5);
        TextLayoutEngine layout = countingLayout(new AtomicInteger());
        ReaderDocument first = document("");
        cache.getOrCompute(first, at(0), DIMENSIONS, layout);
        cache.evict("document");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void evictionOfNamedDocumentKeepsOtherNamedBook() {
        PageCache cache = new PageCache(5);
        TextLayoutEngine layout = countingLayout(new AtomicInteger());
        ReaderDocument first = document("first");
        ReaderDocument second = document("second");
        cache.getOrCompute(first, at(0), DIMENSIONS, layout);
        cache.getOrCompute(second, at(0), DIMENSIONS, layout);
        cache.evictForDocument(first);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void differentDimensionsAreDifferentPageKeys() {
        PageCache cache = new PageCache(5);
        AtomicInteger calls = new AtomicInteger();
        TextLayoutEngine layout = countingLayout(calls);
        ReaderDocument book = document("book");
        cache.getOrCompute(book, at(0), DIMENSIONS, layout);
        cache.getOrCompute(book, at(0), new PageDimensions(640, 600, 10, 10, 10, 10), layout);
        assertThat(cache.size()).isEqualTo(2);
        assertThat(calls).hasValue(2);
    }

    @Test
    void leastRecentlyUsedPageIsDiscarded() {
        PageCache cache = new PageCache(2);
        AtomicInteger calls = new AtomicInteger();
        TextLayoutEngine layout = countingLayout(calls);
        ReaderDocument book = document("book");
        cache.getOrCompute(book, at(0), DIMENSIONS, layout);
        cache.getOrCompute(book, at(10), DIMENSIONS, layout);
        cache.getOrCompute(book, at(0), DIMENSIONS, layout);
        cache.getOrCompute(book, at(20), DIMENSIONS, layout);
        cache.getOrCompute(book, at(10), DIMENSIONS, layout);
        assertThat(calls).hasValue(4);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void hugeConfiguredMaximumDoesNotPreallocateMemory() {
        PageCache cache = new PageCache(Integer.MAX_VALUE);
        assertThat(cache.size()).isZero();
        assertThat(cache.getMaxSize()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void nullLayoutIsNotCachedAndNextTryRecomputes() {
        PageCache cache = new PageCache(2);
        TextLayoutEngine layout = mock(TextLayoutEngine.class);
        ReaderDocument book = document("book");
        assertThat(cache.getOrCompute(book, at(0), DIMENSIONS, layout)).isNull();
        assertThat(cache.getOrCompute(book, at(0), DIMENSIONS, layout)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void clearDropsAllPageKeys() {
        PageCache cache = new PageCache(2);
        cache.getOrCompute(document("book"), at(0), DIMENSIONS,
                countingLayout(new AtomicInteger()));
        cache.clear();
        assertThat(cache.size()).isZero();
    }
}
