package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.port.out.search.SearchQueryService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.application.search.SearchIndexProgress;
import com.myhomelibcorp.application.search.SearchIndexPerformanceReport;
import com.myhomelibcorp.application.search.SearchIndexUnavailableException;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
@Slf4j
public class LuceneSearchService implements SearchIndexer, SearchQueryService, IndexRebuilder {

    private Directory directory;
    private final Analyzer analyzer;
    private final BookQueryRepository bookQueryRepository;
    private final LuceneDocumentMapper documentMapper = new LuceneDocumentMapper();
    private final LuceneUnifiedFilterBuilder unifiedFilterBuilder = new LuceneUnifiedFilterBuilder();
    private final LuceneQueryNormalizer queryNormalizer;

    @Value("${app.search.commit-interval:10000}")
    private int commitInterval;

    private IndexWriter indexWriter;
    private SearcherManager searcherManager;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private int indexedSinceLastCommit = 0;
    private volatile boolean atomicUpdate = false;
    private volatile SearchIndexPerformanceReport lastPerformanceReport;
    private java.util.function.IntConsumer commitObserver = ignored -> { };
    private Runnable fullRebuildObserver = () -> { };
    private volatile boolean queryAvailable = false;
    private volatile String queryUnavailableReason = "Пошуковий індекс ще не готовий";

    public LuceneSearchService(Directory directory, Analyzer analyzer, QueryParser queryParser,
                               BookQueryRepository bookQueryRepository) {
        this.directory = directory;
        this.analyzer = analyzer;
        this.bookQueryRepository = bookQueryRepository;
        this.queryNormalizer = new LuceneQueryNormalizer(queryParser);
    }

    @PostConstruct
    public void init() {
        log.info("Ініціалізація LuceneSearchService...");
        LuceneIndexWriterFactory.OpenedIndex opened = LuceneIndexWriterFactory.open(directory, analyzer);
        directory = opened.directory();
        indexWriter = opened.writer();
        searcherManager = opened.searcherManager();
        log.info("LuceneSearchService ініціалізовано");
    }

    // Package-private hooks owned by LuceneCollectionIndexLifecycle.
    synchronized void setCommitObserver(java.util.function.IntConsumer observer) {
        commitObserver = observer == null ? ignored -> { } : observer;
    }

    synchronized void setFullRebuildObserver(Runnable observer) {
        fullRebuildObserver = observer == null ? () -> { } : observer;
    }

    synchronized void setQueryAvailability(boolean available, String reason) {
        queryAvailable = available;
        queryUnavailableReason = available ? ""
                : (reason == null || reason.isBlank() ? "Пошуковий індекс ще не готовий" : reason);
    }

    synchronized void switchDirectory(Directory nextDirectory) {
        if (isClosed.get()) throw new IllegalStateException("LuceneSearchService is closed");
        closeIndexResources(false);
        LuceneIndexWriterFactory.OpenedIndex opened = LuceneIndexWriterFactory.open(nextDirectory, analyzer);
        directory = opened.directory();
        indexWriter = opened.writer();
        searcherManager = opened.searcherManager();
        indexedSinceLastCommit = 0;
        atomicUpdate = false;
        setQueryAvailability(false, "Пошуковий індекс перемикається на іншу колекцію");
    }

    synchronized void closeIndexForSwitch() {
        if (isClosed.get()) return;
        closeIndexResources(false);
    }
    // ==================== SEARCH INDEXER ====================

    @Override
    public void indexBook(Book book) {
        if (book == null || isClosed.get()) return;
        indexSnapshot(BookSnapshot.fromBook(book));
    }

    @Override
    public void indexSnapshot(BookSnapshot snapshot) {
        if (snapshot == null || isClosed.get()) return;
        try {
            Document doc = documentMapper.toDocument(snapshot);
            indexWriter.updateDocument(new Term("id", snapshot.getId().asString()), doc);
            indexedSinceLastCommit++;
            if (!atomicUpdate && indexedSinceLastCommit >= commitInterval) {
                commit();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося проіндексувати книгу " + snapshot.getId(), e);
        }
    }

    @Override
    public void indexAll(List<Book> books) {
        if (books == null || books.isEmpty() || isClosed.get()) return;
        int indexed = 0;
        try {
            for (Book book : books) {
                if (book == null) continue;
                BookSnapshot snapshot = BookSnapshot.fromBook(book);
                indexWriter.updateDocument(new Term("id", snapshot.getId().asString()),
                        documentMapper.toDocument(snapshot));
                indexed++;
            }
            indexedSinceLastCommit += indexed;
            if (!atomicUpdate && indexedSinceLastCommit >= commitInterval) commit();
            log.info("Проіндексовано/оновлено {} книг", indexed);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося пакетно оновити Lucene індекс", e);
        }
    }

    @Override
    public void deleteBook(BookId bookId) {
        if (bookId == null || isClosed.get()) return;
        try {
            indexWriter.deleteDocuments(new Term("id", bookId.asString()));
            indexedSinceLastCommit++;
            if (!atomicUpdate && indexedSinceLastCommit >= commitInterval) {
                commit();
            }
            log.debug("Видалено з індексу: {}", bookId);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося видалити книгу з Lucene: " + bookId, e);
        }
    }

    @Override
    public synchronized void clearIndex() {
        if (isClosed.get() || indexWriter == null) return;
        boolean begun = false;
        try {
            beginAtomicUpdate();
            begun = true;
            indexWriter.deleteAll();
            indexedSinceLastCommit = 0;
            commit();
            log.info("Lucene index cleared before collection-context switch");
        } catch (Exception error) {
            if (begun) {
                try { rollbackAtomicUpdate(); } catch (RuntimeException rollbackFailure) { error.addSuppressed(rollbackFailure); }
            }
            throw new IllegalStateException("Не вдалося очистити пошуковий індекс", error);
        }
    }

    /**
     * Bounded full rebuild: keyset repository stream, one atomic publish,
     * progress/heap telemetry and no materialization of the full catalogue.
     */
    @Override
    public synchronized void rebuildIndex() {
        rebuildIndex(null, null);
    }

    @Override
    public synchronized void rebuildIndex(AtomicBoolean cancelFlag, Consumer<SearchIndexProgress> progressListener) {
        if (isClosed.get()) return;
        if (indexWriter == null) {
            log.warn("IndexWriter is null, cannot rebuild index");
            return;
        }
        log.info("Початок повної перебудови індексу (bounded keyset + atomic commit)...");
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        long indexed = 0;
        long dbReadNanos = 0;
        long documentBuildNanos = 0;
        long luceneWriteNanos = 0;
        long mergeWaitNanos = 0;
        long commitNanos = 0;
        long peakHeap = LuceneIndexMetrics.usedHeapBytes();
        long gcCollectionsBefore = LuceneIndexMetrics.totalGcCollections();
        long gcTimeBefore = LuceneIndexMetrics.totalGcTimeMs();
        long total = Math.max(0L, bookQueryRepository.countAll());
        LuceneRebuildSupport.notifyProgress(progressListener, 0, total);

        beginAtomicUpdate();
        try {
            // Delete and rebuild stay uncommitted until the entire catalog is indexed.
            indexWriter.deleteAll();
            indexedSinceLastCommit = 0;

            // Dedicated repository projection: skips tombstones in SQL, pages 5k active rows at a time
            // and reads only fields consumed by Lucene. This avoids constructing hundreds of thousands
            // of full Book/BookMetadata/BookFile aggregates during a full rebuild.
            try (var snapshots = bookQueryRepository.streamSearchSnapshots()) {
                var iterator = snapshots.iterator();
                while (true) {
                    LuceneRebuildSupport.checkCancelled(cancelFlag);
                    long dbStarted = System.nanoTime();
                    boolean hasNext = iterator.hasNext();
                    BookSnapshot snapshot = hasNext ? iterator.next() : null;
                    dbReadNanos += System.nanoTime() - dbStarted;
                    if (!hasNext) break;
                    if (snapshot == null) continue;

                    long buildStarted = System.nanoTime();
                    Document document = documentMapper.toDocument(snapshot);
                    documentBuildNanos += System.nanoTime() - buildStarted;

                    long writeStarted = System.nanoTime();
                    indexWriter.updateDocument(new Term("id", snapshot.getId().asString()), document);
                    luceneWriteNanos += System.nanoTime() - writeStarted;
                    indexed++;

                    if (indexed % 1_000 == 0) {
                        LuceneRebuildSupport.notifyProgress(progressListener, indexed, total);
                        peakHeap = Math.max(peakHeap, LuceneIndexMetrics.usedHeapBytes());
                    }
                    if (indexed % 50_000 == 0) {
                        long elapsed = LuceneIndexMetrics.elapsedMs(startedNanos);
                        log.info("⏳ Підготовлено {} книг за {} мс (commit буде лише фінальний)", indexed, elapsed);
                    }
                }
            }
            LuceneRebuildSupport.checkCancelled(cancelFlag);

            // Merge work required for durability is accounted for by the final commit timing.
            mergeWaitNanos = 0L;

            // One final commit; this also ends the atomic-update section.
            long commitStarted = System.nanoTime();
            commit();
            commitNanos = System.nanoTime() - commitStarted;
            notifyFullRebuildObserver();
            LuceneRebuildSupport.notifyProgress(progressListener, indexed, total > 0 ? total : indexed);
            peakHeap = Math.max(peakHeap, LuceneIndexMetrics.usedHeapBytes());

            lastPerformanceReport = LuceneRebuildSupport.report(startedAt, "SUCCESS", indexed, total, startedNanos,
                    dbReadNanos, documentBuildNanos, luceneWriteNanos, mergeWaitNanos, commitNanos, peakHeap,
                    gcCollectionsBefore, gcTimeBefore, directory);
            log.info("✅ Індекс перебудовано: {} документів за {} мс ({} книг/с); DB={} мс, Document={} мс, write={} мс, mergeWait={} мс, commit={} мс",
                    indexed, lastPerformanceReport.totalDurationMs(), String.format("%.1f", lastPerformanceReport.documentsPerSecond()), lastPerformanceReport.dbReadMs(),
                    lastPerformanceReport.documentBuildMs(), lastPerformanceReport.luceneWriteMs(),
                    lastPerformanceReport.mergeWaitMs(), lastPerformanceReport.commitMs());

        } catch (LuceneRebuildSupport.CancelledException e) {
            try { rollbackAtomicUpdate(); } catch (Exception rollbackFailure) { e.addSuppressed(rollbackFailure); }
            lastPerformanceReport = LuceneRebuildSupport.report(startedAt, "CANCELLED", indexed, total, startedNanos,
                    dbReadNanos, documentBuildNanos, luceneWriteNanos, mergeWaitNanos, commitNanos, peakHeap,
                    gcCollectionsBefore, gcTimeBefore, directory);
            log.info("Перебудову Lucene скасовано; попередній committed індекс збережено");
            throw e;
        } catch (Exception e) {
            try { rollbackAtomicUpdate(); } catch (Exception rollbackFailure) { e.addSuppressed(rollbackFailure); }
            lastPerformanceReport = LuceneRebuildSupport.report(startedAt, "FAILED", indexed, total, startedNanos,
                    dbReadNanos, documentBuildNanos, luceneWriteNanos, mergeWaitNanos, commitNanos, peakHeap,
                    gcCollectionsBefore, gcTimeBefore, directory);
            log.error("Помилка перебудови індексу; попередній committed індекс збережено", e);
            throw new IllegalStateException("Не вдалося перебудувати пошуковий індекс", e);
        }
    }

@Override
    public int getDocumentCount() {
        if (isClosed.get() || indexWriter == null) return 0;
        return indexWriter.getDocStats().numDocs;
    }

    @Override
    public Optional<SearchIndexPerformanceReport> lastPerformanceReport() {
        return Optional.ofNullable(lastPerformanceReport);
    }

    @Override
    public int getIndexedDocumentCount() {
        return getDocumentCount();
    }

    @Override
    public synchronized void beginAtomicUpdate() {
        if (isClosed.get() || indexWriter == null) return;
        if (atomicUpdate) throw new IllegalStateException("Lucene atomic update is already active");
        try {
            // Establish an explicit rollback point for any older non-atomic writes.
            indexWriter.commit();
            searcherManager.maybeRefreshBlocking();
            indexedSinceLastCommit = 0;
            atomicUpdate = true;
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося почати атомарне оновлення Lucene", e);
        }
    }

    @Override
    public synchronized void rollbackAtomicUpdate() {
        if (!atomicUpdate || indexWriter == null) return;
        try {
            if (searcherManager != null) searcherManager.close();
            indexWriter.rollback();
            LuceneIndexWriterFactory.OpenedIndex reopened = LuceneIndexWriterFactory.open(directory, analyzer);
            directory = reopened.directory();
            indexWriter = reopened.writer();
            searcherManager = reopened.searcherManager();
            indexedSinceLastCommit = 0;
            atomicUpdate = false;
        } catch (RuntimeException | IOException e) {
            atomicUpdate = false;
            throw new IllegalStateException("Не вдалося відкотити Lucene до попереднього commit", e);
        }
    }

    @Override
    public synchronized void commit() {
        if (isClosed.get() || indexWriter == null) return;
        try {
            indexWriter.commit();
            searcherManager.maybeRefreshBlocking();
            indexedSinceLastCommit = 0;
            atomicUpdate = false;
            notifyCommitObserver();
            log.debug("Lucene індекс закомічено");
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося закомітити Lucene індекс", e);
        }
    }

    private void notifyCommitObserver() {
        try { commitObserver.accept(getDocumentCount()); }
        catch (RuntimeException observerFailure) {
            log.warn("Lucene commit observer failed: {}", observerFailure.toString());
        }
    }

    private void notifyFullRebuildObserver() {
        try { fullRebuildObserver.run(); }
        catch (RuntimeException observerFailure) {
            log.warn("Lucene full-rebuild observer failed: {}", observerFailure.toString());
        }
    }

    private void closeIndexResources(boolean commitBeforeClose) {
        try {
            LuceneIndexResourceCloser.close(searcherManager, indexWriter, directory, atomicUpdate,
                    commitBeforeClose, this::notifyCommitObserver);
        } finally {
            searcherManager = null; indexWriter = null; directory = null;
            atomicUpdate = false; indexedSinceLastCommit = 0;
            setQueryAvailability(false, "Пошуковий індекс закрито");
        }
    }
    // ==================== SEARCH QUERY SERVICE ====================

    @Override
    public SearchResult search(SearchRequest request) {
        if (request == null) return SearchResult.empty();
        if (isClosed.get() || indexWriter == null || searcherManager == null) {
            throw new SearchIndexUnavailableException("Пошуковий індекс зараз недоступний");
        }
        if (!queryAvailable) {
            throw new SearchIndexUnavailableException(queryUnavailableReason);
        }
        try {
            return LuceneSearchExecutor.search(request, searcherManager, queryNormalizer, unifiedFilterBuilder);
        } catch (SearchIndexUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Помилка пошуку: {}", request.text(), e);
            throw new IllegalStateException("Помилка пошукового індексу", e);
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (isClosed.getAndSet(true)) return;
        log.info("Закриття LuceneSearchService...");
        try {
            closeIndexResources(true);
        } catch (RuntimeException e) {
            log.error("Помилка закриття LuceneSearchService", e);
        }
        log.info("LuceneSearchService завершено");
    }}