package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LuceneResourceLifecycleTest {
    @TempDir Path temp;

    @Test
    void standaloneConstructionDoesNotCommitEveryDocument() {
        try (var analyzer = new StandardAnalyzer()) {
            var search = new LuceneSearchService(new ByteBuffersDirectory(), analyzer,
                    new QueryParser("title", analyzer), mock(BookQueryRepository.class));
            search.init();
            try {
                AtomicInteger commits = new AtomicInteger();
                search.setCommitObserver(count -> commits.incrementAndGet());
                for (int i = 0; i < 25; i++) search.indexSnapshot(BookSnapshot.builder()
                        .id(BookId.generate()).title("Book " + i).build());
                assertThat(commits).hasValue(0);
                search.commit();
                assertThat(commits).hasValue(1);
            } finally { search.close(); }
        }
    }

    @Test
    void lockRetryUsesAFreshWriterConfiguration() throws Exception {
        try (var analyzer = new StandardAnalyzer(); var directory = FSDirectory.open(temp)) {
            IndexWriter owner = new IndexWriter(directory, new IndexWriterConfig(analyzer));
            CountDownLatch firstFailure = new CountDownLatch(1);
            Directory observed = new FilterDirectory(directory) {
                @Override public Lock obtainLock(String name) throws IOException {
                    try { return super.obtainLock(name); }
                    catch (LockObtainFailedException failure) { firstFailure.countDown(); throw failure; }
                }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                var pending = worker.submit(() -> LuceneIndexWriterFactory.open(observed, analyzer));
                assertThat(firstFailure.await(2, TimeUnit.SECONDS)).isTrue();
                owner.close();
                var opened = pending.get(4, TimeUnit.SECONDS);
                LuceneIndexResourceCloser.close(opened.searcherManager(), opened.writer(), observed, false, false, null);
            } finally {
                owner.close();
                worker.shutdownNow();
                assertThat(worker.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void failedCommitStillReleasesWriterSearcherAndDirectory() throws Exception {
        IndexWriter writer = mock(IndexWriter.class);
        SearcherManager searcher = mock(SearcherManager.class);
        Directory directory = mock(Directory.class);
        IOException error = new IOException("disk full");
        doThrow(error).when(writer).commit();
        var failure = assertThrows(IllegalStateException.class, () ->
                LuceneIndexResourceCloser.close(searcher, writer, directory, false, true, null));
        assertThat(failure.getCause()).isSameAs(error);
        verify(searcher).close();
        verify(writer).close();
        verify(directory).close();
    }

    @Test
    void observerFailureStillReleasesResources() throws Exception {
        IndexWriter writer = mock(IndexWriter.class);
        Directory directory = mock(Directory.class);
        assertThrows(IllegalStateException.class, () -> LuceneIndexResourceCloser.close(
                null, writer, directory, false, true, () -> { throw new IllegalArgumentException("observer"); }));
        verify(writer).close();
        verify(directory).close();
    }

    @Test
    void lifecycleUsesTheSearchMonitorForTransitions() throws Exception {
        LuceneSearchService search = mock(LuceneSearchService.class);
        var lifecycle = new LuceneCollectionIndexLifecycle(search, mock(BookQueryRepository.class));
        var active = LuceneCollectionIndexLifecycle.class.getDeclaredField("activeCollectionId");
        active.setAccessible(true);
        active.set(lifecycle, "collection");
        doAnswer(call -> { assertThat(Thread.holdsLock(search)).isTrue(); return null; })
                .when(search).setQueryAvailability(anyBoolean(), any());
        doAnswer(call -> { assertThat(Thread.holdsLock(search)).isTrue(); return null; }).when(search).commit();
        lifecycle.markCurrentIndexDirty();
        lifecycle.closeCurrentIndex();
        verify(search).setQueryAvailability(eq(false), any());
        verify(search).commit();
    }
}
