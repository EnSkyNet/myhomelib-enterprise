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
}
