package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.port.out.search.SearchQueryService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.index.TieredMergePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // ⚡ ОПТИМІЗАЦІЯ: лічильник для періодичного commit під час перебудови
    private static final int REBUILD_COMMIT_INTERVAL = 50_000;

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

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

        // ⚡ ОПТИМІЗАЦІЯ 1: Збільшений RAM буфер для швидкої індексації
        config.setRAMBufferSizeMB(512.0);              // Було 64 MB
        config.setMaxBufferedDocs(10000);              // Було 1000

        // ⚡ ОПТИМІЗАЦІЯ 2: Не комітити при закритті (commit в кінці)
        config.setCommitOnClose(false);

        // ⚡ ОПТИМІЗАЦІЯ 3: Вимкнути compound file для швидшого запису
        config.setUseCompoundFile(false);

        // ⚡ ОПТИМІЗАЦІЯ 4: Налаштування політики злиття для великих індексів
        TieredMergePolicy mergePolicy = new TieredMergePolicy();
        mergePolicy.setMaxMergeAtOnce(10);             // Більше сегментів за раз
        mergePolicy.setSegmentsPerTier(10);            // Більше сегментів на рівень
        mergePolicy.setMaxMergedSegmentMB(5120);       // Максимальний розмір сегмента 5GB
        // У Lucene 9.x немає setMergeFactor, використовуємо setMaxMergeAtOnce та setSegmentsPerTier
        mergePolicy.setNoCFSRatio(0.0);                // Вимкнути CFS для всіх сегментів
        config.setMergePolicy(mergePolicy);

        int maxAttempts = 5;
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            try {
                this.indexWriter = new IndexWriter(directory, config);
                log.info("IndexWriter створено (спроба {})", attempt + 1);
                break;
            } catch (LockObtainFailedException e) {
                attempt++;
                log.warn("Індекс заблоковано (спроба {}/{}), очікуємо 1 секунду...", attempt, maxAttempts);
                lastException = e;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Перервано під час очікування lock", ie);
                }
            } catch (IndexFormatTooNewException | IndexFormatTooOldException e) {
                log.warn("Несумісна версія індексу Lucene: {}", e.getMessage());
                try {
                    if (!(directory instanceof FSDirectory fsDirectory)) {
                        throw new IllegalStateException("Cannot recreate non-filesystem Lucene directory", e);
                    }
                    Path indexPath = fsDirectory.getDirectory();
                    directory.close();
                    deleteDirectory(indexPath);
                    Files.createDirectories(indexPath);
                    this.directory = FSDirectory.open(indexPath);
                    config = new IndexWriterConfig(analyzer);
                    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                    config.setRAMBufferSizeMB(512.0);
                    config.setMaxBufferedDocs(10000);
                    config.setCommitOnClose(false);
                    config.setUseCompoundFile(false);
                    TieredMergePolicy newPolicy = new TieredMergePolicy();
                    newPolicy.setMaxMergeAtOnce(10);
                    newPolicy.setSegmentsPerTier(10);
                    newPolicy.setMaxMergedSegmentMB(5120);
                    newPolicy.setNoCFSRatio(0.0);
                    config.setMergePolicy(newPolicy);
                    this.indexWriter = new IndexWriter(this.directory, config);
                    log.info("✅ Несумісний індекс видалено та створено заново: {}", indexPath);
                    break;
                } catch (Exception ex) {
                    log.error("Не вдалося створити новий індекс", ex);
                    throw new RuntimeException("Не вдалося створити IndexWriter", ex);
                }
            } catch (IOException e) {
                log.error("Помилка створення IndexWriter", e);
                throw new RuntimeException("Не вдалося створити IndexWriter", e);
            }
        }

        if (this.indexWriter == null && lastException != null) {
            log.error("Не вдалося створити IndexWriter після {} спроб", maxAttempts);
            throw new RuntimeException("Не вдалося отримати lock на індекс", lastException);
        }

        try {
            this.searcherManager = new SearcherManager(indexWriter, true, true, null);
            log.info("SearcherManager створено");
        } catch (IOException e) {
            log.error("Помилка створення SearcherManager", e);
            throw new RuntimeException("Не вдалося створити SearcherManager", e);
        }

        log.info("LuceneSearchService ініціалізовано з оптимізованими налаштуваннями");
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted((p1, p2) -> -p1.compareTo(p2))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("Не вдалося видалити: {}", p, e);
                            }
                        });
            }
        }
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
            if (indexedSinceLastCommit >= commitInterval) {
                commit();
            }
        } catch (IOException e) {
            log.error("Помилка індексації книги: {}", snapshot.getId(), e);
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
            if (indexedSinceLastCommit >= commitInterval) commit();
            log.info("Проіндексовано/оновлено {} книг", indexed);
        } catch (IOException e) {
            log.error("Помилка пакетної індексації", e);
        }
    }

    @Override
    public void deleteBook(BookId bookId) {
        if (bookId == null || isClosed.get()) return;
        try {
            indexWriter.deleteDocuments(new Term("id", bookId.asString()));
            indexedSinceLastCommit++;
            if (indexedSinceLastCommit >= commitInterval) {
                commit();
            }
            log.debug("Видалено з індексу: {}", bookId);
        } catch (IOException e) {
            log.error("Помилка видалення з індексу: {}", bookId, e);
        }
    }

    /**
     * ⚡ ОПТИМІЗОВАНА перебудова індексу:
     * - Більші сторінки (5000 замість 2000)
     * - Commit тільки кожні 50k документів або в кінці
     * - Вимкнено проміжні commit-и
     */
    @Override
    public synchronized void rebuildIndex() {
        if (isClosed.get()) return;
        if (indexWriter == null) {
            log.warn("IndexWriter is null, cannot rebuild index");
            return;
        }
        log.info("Початок повної перебудови індексу (оптимізовано)...");
        final int pageSize = 5_000;  // ⚡ Збільшено з 2000 до 5000
        int offset = 0;
        long indexed = 0;
        long startTime = System.currentTimeMillis();

        try {
            // ⚡ Видаляємо всі документи без commit
            indexWriter.deleteAll();
            indexedSinceLastCommit = 0;

            while (true) {
                var page = bookQueryRepository.findPage(BookQuery.builder()
                        .pagination(Pagination.of(pageSize, offset))
                        .build());
                List<Book> books = page.content();
                if (books == null || books.isEmpty()) break;

                for (Book book : books) {
                    if (book == null || book.isDeleted()) continue;
                    BookSnapshot snapshot = BookSnapshot.fromBook(book);
                    indexWriter.updateDocument(new Term("id", snapshot.getId().asString()),
                            documentMapper.toDocument(snapshot));
                    indexed++;
                }

                offset += books.size();
                if (books.size() < pageSize) break;

                // ⚡ Commit тільки кожні 50k документів (замість 10k)
                if (indexed % REBUILD_COMMIT_INTERVAL == 0) {
                    indexWriter.commit();
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("⏳ Проіндексовано {} книг за {} мс ({} книг/с)",
                            indexed, elapsed, (indexed * 1000 / Math.max(1, elapsed)));
                }
            }

            // ✅ ОДИН фінальний commit
            indexWriter.commit();
            searcherManager.maybeRefreshBlocking();
            indexedSinceLastCommit = 0;

            long totalTime = System.currentTimeMillis() - startTime;
            double booksPerSecond = indexed * 1000.0 / Math.max(1, totalTime);
            log.info("✅ Індекс перебудовано: {} документів за {} мс ({} книг/с)",
                    indexed, totalTime, String.format("%.1f", booksPerSecond));

        } catch (Exception e) {
            log.error("Помилка перебудови індексу", e);
            throw new IllegalStateException("Не вдалося перебудувати пошуковий індекс", e);
        }
    }

    /**
     * ⚡ ПАРАЛЕЛЬНА перебудова індексу (експериментальна)
     * Використовує багато потоків для швидшої індексації на багатоядерних системах
     */
    public synchronized void rebuildIndexParallel() {
        if (isClosed.get()) return;
        if (indexWriter == null) {
            log.warn("IndexWriter is null, cannot rebuild index");
            return;
        }

        log.info("Початок ПАРАЛЕЛЬНОЇ перебудови індексу...");
        long startTime = System.currentTimeMillis();

        int pageSize = 10_000;
        long total = bookQueryRepository.count(BookQuery.builder().build());
        int totalPages = (int) Math.ceil((double) total / pageSize);
        int threads = Math.min(4, Runtime.getRuntime().availableProcessors());

        log.info("📊 Всього книг: {}, сторінок: {}, потоків: {}", total, totalPages, threads);

        try {
            indexWriter.deleteAll();

            java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
            java.util.concurrent.atomic.AtomicLong indexed = new java.util.concurrent.atomic.AtomicLong(0);

            for (int page = 0; page < totalPages; page++) {
                final int currentPage = page;
                final int currentOffset = page * pageSize;

                futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        var query = BookQuery.builder()
                                .pagination(Pagination.of(pageSize, currentOffset))
                                .build();
                        var books = bookQueryRepository.findPage(query).content();

                        for (Book book : books) {
                            if (book == null || book.isDeleted()) continue;
                            BookSnapshot snapshot = BookSnapshot.fromBook(book);
                            synchronized (indexWriter) {
                                indexWriter.updateDocument(
                                        new Term("id", snapshot.getId().asString()),
                                        documentMapper.toDocument(snapshot)
                                );
                            }
                            indexed.incrementAndGet();
                        }

                        if (indexed.get() % 50_000 == 0) {
                            synchronized (indexWriter) {
                                indexWriter.commit();
                            }
                            log.info("⏳ Паралельно проіндексовано {} книг", indexed.get());
                        }
                    } catch (Exception e) {
                        log.error("Помилка паралельної індексації сторінки {}", currentPage, e);
                    }
                }, executor));
            }

            java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(new java.util.concurrent.CompletableFuture[0])
            ).join();

            executor.shutdown();
            try {
                executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            indexWriter.commit();
            searcherManager.maybeRefreshBlocking();
            indexedSinceLastCommit = 0;

            long totalTime = System.currentTimeMillis() - startTime;
            double booksPerSecond = indexed.get() * 1000.0 / Math.max(1, totalTime);
            log.info("✅ Паралельний індекс перебудовано: {} документів за {} мс ({} книг/с)",
                    indexed.get(), totalTime, String.format("%.1f", booksPerSecond));

        } catch (Exception e) {
            log.error("Помилка паралельної перебудови індексу", e);
            throw new IllegalStateException("Не вдалося перебудувати пошуковий індекс", e);
        }
    }

    @Override
    public int getDocumentCount() {
        if (isClosed.get() || indexWriter == null) return 0;
        try {
            return indexWriter.getDocStats().numDocs;
        } catch (Exception e) {
            log.error("Помилка отримання кількості документів", e);
            return 0;
        }
    }

    @Override
    public int getIndexedDocumentCount() {
        return getDocumentCount();
    }

    @Override
    public void commit() {
        if (isClosed.get() || indexWriter == null) return;
        try {
            indexWriter.commit();
            searcherManager.maybeRefresh();
            indexedSinceLastCommit = 0;
            log.debug("Lucene індекс закомічено");
        } catch (IOException e) {
            log.error("Помилка commit", e);
        }
    }

    // ==================== SEARCH QUERY SERVICE ====================

    @Override
    public SearchResult search(SearchRequest request) {
        if (request == null || isClosed.get() || indexWriter == null) return SearchResult.empty();
        long start = System.currentTimeMillis();
        List<BookId> bookIds = new ArrayList<>();

        try {
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                BooleanQuery.Builder b = new BooleanQuery.Builder();
                String text = request.text() == null ? "" : request.text().trim();
                if (!text.isBlank()) {
                    b.add(queryNormalizer.parse(text, request.mode()), BooleanClause.Occur.MUST);
                }
                if (request.authorId() != null) {
                    b.add(new TermQuery(new Term("author_id", request.authorId().asString())), BooleanClause.Occur.FILTER);
                }
                if (request.genreId() != null) {
                    b.add(new TermQuery(new Term("genre_id", request.genreId().asString())), BooleanClause.Occur.FILTER);
                }
                if (request.language() != null) {
                    b.add(new TermQuery(new Term("language", request.language().value().toLowerCase(java.util.Locale.ROOT))), BooleanClause.Occur.FILTER);
                }
                if (request.ratingFrom() != null || request.ratingTo() != null) {
                    int lo = request.ratingFrom() == null ? Integer.MIN_VALUE : request.ratingFrom();
                    int hi = request.ratingTo() == null ? Integer.MAX_VALUE : request.ratingTo();
                    b.add(IntPoint.newRangeQuery("library_rate_num", lo, hi), BooleanClause.Occur.FILTER);
                }
                if (request.yearFrom() != null || request.yearTo() != null) {
                    int lo = request.yearFrom() == null ? Integer.MIN_VALUE : request.yearFrom();
                    int hi = request.yearTo() == null ? Integer.MAX_VALUE : request.yearTo();
                    b.add(IntPoint.newRangeQuery("year_num", lo, hi), BooleanClause.Occur.FILTER);
                }
                if (request.addedFrom() != null || request.addedTo() != null) {
                    long lo = request.addedFrom() == null ? Long.MIN_VALUE : request.addedFrom().toEpochDay();
                    long hi = request.addedTo() == null ? Long.MAX_VALUE : request.addedTo().toEpochDay();
                    b.add(LongPoint.newRangeQuery("created_day", lo, hi), BooleanClause.Occur.FILTER);
                }
                if (request.localOnly() != null) {
                    b.add(new TermQuery(new Term("local", request.localOnly() ? "1" : "0")), BooleanClause.Occur.FILTER);
                }
                unifiedFilterBuilder.addTo(b, request.filterSpec());
                b.add(new TermQuery(new Term("deleted", "0")), BooleanClause.Occur.FILTER);
                Query query = b.build().clauses().isEmpty() ? new MatchAllDocsQuery() : b.build();

                int offset = Math.max(0, request.offset());
                int limit = Math.max(1, request.limit());
                int requested = Math.min(100_000, offset + limit);
                TopDocs top = searcher.search(query, requested);
                ScoreDoc[] hits = top.scoreDocs;
                for (int i = offset; i < hits.length && bookIds.size() < limit; i++) {
                    Document doc = searcher.doc(hits[i].doc);
                    String id = doc.get("id");
                    if (id != null && !id.isEmpty()) bookIds.add(BookId.fromString(id));
                }

                long elapsed = System.currentTimeMillis() - start;
                return new SearchResult(bookIds, Math.toIntExact(Math.min(Integer.MAX_VALUE, top.totalHits.value)),
                        offset / limit, limit, elapsed);
            } finally {
                searcherManager.release(searcher);
            }
        } catch (Exception e) {
            log.error("Помилка пошуку: {}", request.text(), e);
            return SearchResult.empty();
        }
    }

    public boolean isClosed() {
        return isClosed.get();
    }

    @PreDestroy
    public void close() {
        if (isClosed.get()) return;

        log.info("Закриття LuceneSearchService...");

        try {
            if (indexWriter != null) {
                indexWriter.commit();
                indexWriter.close();
                log.info("IndexWriter закрито");
            }
            if (searcherManager != null) {
                searcherManager.close();
                log.info("SearcherManager закрито");
            }
            if (directory != null) {
                directory.close();
                log.info("Directory закрито");
            }
        } catch (IOException e) {
            log.error("Помилка закриття LuceneSearchService", e);
        }
        isClosed.set(true);
        log.info("LuceneSearchService завершено");
    }
}