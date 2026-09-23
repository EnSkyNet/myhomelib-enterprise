package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LuceneCollectionIndexLifecycleCrashSafetyTest {

    @TempDir
    Path temp;

    private String previousDataDir;

    @BeforeEach
    void configureDataDir() {
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("app-data").toString());
    }

    @AfterEach
    void restoreDataDir() {
        if (previousDataDir == null) System.clearProperty("myhomelib.dataDir");
        else System.setProperty("myhomelib.dataDir", previousDataDir);
    }

    @Test
    void dirtyActivationKeepsLastCommittedIndexAsAtomicRebuildRollbackPoint() throws Exception {
        LuceneSearchService search = mock(LuceneSearchService.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(search.getDocumentCount()).thenReturn(42);

        LuceneCollectionIndexLifecycle lifecycle = new LuceneCollectionIndexLifecycle(search, books);
        lifecycle.registerCommitObserver();

        Path database = temp.resolve("library.db");
        Files.writeString(database, "sqlite-placeholder");
        Collection collection = new Collection("lucene-crash", "Lucene crash safety", null,
                database.toString(), 0, null, null, null, null);

        // There is deliberately no freshness marker. Activation therefore requires a rebuild while
        // a previously committed 42-document index is assumed to exist.
        boolean reusable = lifecycle.activateCollectionIndex(collection);

        assertThat(reusable).isFalse();
        verify(search).setQueryAvailability(false, "Пошуковий індекс перебудовується: freshness marker is absent");
        verify(search, never()).clearIndex();
    }


    @Test
    void dirtyMarkerWriteFailureIsFailClosedBeforeCatalogMutationCanProceed() throws Exception {
        LuceneSearchService search = mock(LuceneSearchService.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(search.getDocumentCount()).thenReturn(0);

        Path database = temp.resolve("library-dirty-strict.db");
        Files.writeString(database, "sqlite-placeholder");
        Collection collection = new Collection("dirty-strict", "Dirty strict", null,
                database.toString(), 0, null, null, null, null);

        LuceneCollectionIndexLifecycle lifecycle = new LuceneCollectionIndexLifecycle(search, books);
        lifecycle.registerCommitObserver();
        lifecycle.activateCollectionIndex(collection);

        Path marker = temp.resolve("app-data/search-index/dirty-strict.state");
        Files.deleteIfExists(marker);
        Path foreign = temp.resolve("foreign-dirty-marker.txt");
        Files.writeString(foreign, "FOREIGN");
        try {
            Files.createSymbolicLink(marker, foreign);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are not supported");
        }

        assertThatThrownBy(lifecycle::markCurrentIndexDirty)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mandatory dirty Lucene marker");
        assertThat(Files.readString(foreign)).isEqualTo("FOREIGN");
        assertThat(Files.isSymbolicLink(marker)).isTrue();
        verify(search).setQueryAvailability(false, "Пошуковий індекс синхронізується з каталогом");
    }

    @Test
    void symlinkFreshnessMarkerIsNotFollowedOrDeleted() throws Exception {
        LuceneSearchService search = mock(LuceneSearchService.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(search.getDocumentCount()).thenReturn(0);

        Path database = temp.resolve("library-symlink-marker.db");
        Files.writeString(database, "sqlite-placeholder");
        Collection collection = new Collection("marker-link", "Lucene marker safety", null,
                database.toString(), 0, null, null, null, null);

        Path searchRoot = temp.resolve("app-data/search-index");
        Files.createDirectories(searchRoot);
        Path foreign = temp.resolve("foreign-marker.txt");
        Files.writeString(foreign, "FOREIGN");
        Path marker = searchRoot.resolve("marker-link.state");
        Files.createSymbolicLink(marker, foreign);

        LuceneCollectionIndexLifecycle lifecycle = new LuceneCollectionIndexLifecycle(search, books);
        lifecycle.registerCommitObserver();
        assertThat(lifecycle.activateCollectionIndex(collection)).isFalse();

        assertThat(Files.readString(foreign)).isEqualTo("FOREIGN");
        assertThat(Files.isSymbolicLink(marker)).isTrue();
    }

    @Test
    void symlinkIndexDirectoryIsRejectedBeforeLuceneCanWriteOutsideDataDir() throws Exception {
        LuceneSearchService search = mock(LuceneSearchService.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        Path database = temp.resolve("library-symlink-index.db");
        Files.writeString(database, "sqlite-placeholder");
        Collection collection = new Collection("index-link", "Lucene index safety", null,
                database.toString(), 0, null, null, null, null);

        Path searchRoot = temp.resolve("app-data/search-index");
        Files.createDirectories(searchRoot);
        Path foreignDir = temp.resolve("foreign-index");
        Files.createDirectories(foreignDir);
        Files.createSymbolicLink(searchRoot.resolve("index-link"), foreignDir);

        LuceneCollectionIndexLifecycle lifecycle = new LuceneCollectionIndexLifecycle(search, books);
        lifecycle.registerCommitObserver();
        assertThatThrownBy(() -> lifecycle.activateCollectionIndex(collection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot activate Lucene index");
        try (var entries = Files.list(foreignDir)) {
            assertThat(entries.toList()).isEmpty();
        }
        verify(search, never()).switchDirectory(any());
    }
}
