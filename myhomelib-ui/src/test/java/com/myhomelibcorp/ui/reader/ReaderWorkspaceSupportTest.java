package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.TocEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderWorkspaceSupportTest {

    @Test
    void normalizesReaderFileAndLanguageHelpers() {
        assertThat(ReaderWorkspaceSupport.isReaderEntry("books/novel.fb2")).isTrue();
        assertThat(ReaderWorkspaceSupport.isReaderEntry("cover.jpg")).isFalse();
        assertThat(ReaderWorkspaceSupport.readerSuffix("folder/book.epub")).isEqualTo(".epub");
        assertThat(ReaderWorkspaceSupport.readerSuffix("book")).isEqualTo(".book");
        assertThat(ReaderWorkspaceSupport.normalizeLanguageCode(" UK_ua ")).isEqualTo("uk");
        assertThat(ReaderWorkspaceSupport.normalizeOptionalLanguageCode(null)).isEmpty();
    }

    @Test
    void replacementCancellationCancelsPreviousToken() {
        AtomicBoolean previous = new AtomicBoolean(false);
        AtomicReference<AtomicBoolean> ref = new AtomicReference<>(previous);

        AtomicBoolean next = ReaderWorkspaceSupport.replaceCancellation(ref);

        assertThat(previous).isTrue();
        assertThat(next).isFalse();
        assertThat(ref).hasValue(next);
        ReaderWorkspaceSupport.cancelRequest(ref);
        assertThat(next).isTrue();
        assertThat(ref).hasValue(null);
    }

    @Test
    void unwrapsAsyncWrapperAndFlattensNestedTocWithoutMutatingHierarchy() {
        IllegalStateException root = new IllegalStateException("boom");
        assertThat(ReaderWorkspaceSupport.unwrap(new CompletionException(root))).isSameAs(root);

        TocEntry child = new TocEntry("Child", 20L, 1, List.of());
        TocEntry parent = new TocEntry("Parent", 10L, 0, List.of(child));
        List<TocEntry> flat = ReaderWorkspaceSupport.flattenToc(List.of(parent));

        assertThat(flat).extracting(TocEntry::title).containsExactly("Parent", "Child");
        assertThat(flat).extracting(TocEntry::level).containsExactly(0, 1);
        assertThat(flat).allMatch(entry -> entry.children().isEmpty());
    }
}
