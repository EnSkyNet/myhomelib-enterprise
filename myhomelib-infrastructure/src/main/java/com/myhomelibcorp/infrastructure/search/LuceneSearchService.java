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

        // The injected Directory already points at the portable/non-portable configured index path.
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(64.0);
        config.setMaxBufferedDocs(1000);

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
                    config.setRAMBufferSizeMB(64.0);
                    config.setMaxBufferedDocs(1000);
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

        log.info("LuceneSearchService ініціалізовано");
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
                indexWriter.updateDocument(new Term("id", snapshot.getId().asString()), documentMapper.toDocument(snapshot));
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

    @Override
    public synchronized void rebuildIndex() {
        if (isClosed.get()) return;
        if (indexWriter == null) {
            log.warn("IndexWriter is null, cannot rebuild index");
            return;
        }
        log.info("Початок повної перебудови індексу...");
        final int pageSize = 2_000;
        int offset = 0;
        long indexed = 0;
        try {
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
                    indexWriter.updateDocument(new Term("id", snapshot.getId().asString()), documentMapper.toDocument(snapshot));
                    indexed++;
                }
                offset += books.size();
                if (books.size() < pageSize) break;
                if (indexed % 10_000 == 0) indexWriter.commit();
            }
            indexWriter.commit();
            searcherManager.maybeRefreshBlocking();
            indexedSinceLastCommit = 0;
            log.info("✅ Індекс перебудовано: {} документів", indexed);
        } catch (Exception e) {
            log.error("Помилка перебудови індексу", e);
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
    public List<String> searchBookIds(String queryText, int limit) {
        if (queryText == null || queryText.isBlank() || isClosed.get()) return List.of();
        SearchResult result = search(SearchRequest.builder().text(queryText).limit(limit).build());
        return result.bookIds().stream().map(BookId::asString).toList();
    }

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

    @Override
    public int getIndexedDocumentCount() {
        return getDocumentCount();
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