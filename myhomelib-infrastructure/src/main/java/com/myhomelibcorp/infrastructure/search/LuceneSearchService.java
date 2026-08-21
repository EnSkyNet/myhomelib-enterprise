package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
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
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class LuceneSearchService implements SearchIndexer, SearchQueryService, IndexRebuilder {

    private final Directory directory;
    private final Analyzer analyzer;
    private final QueryParser queryParser;

    @Value("${app.search.commit-interval:10000}")
    private int commitInterval;

    private IndexWriter indexWriter;
    private SearcherManager searcherManager;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private int indexedSinceLastCommit = 0;

    public LuceneSearchService(Directory directory, Analyzer analyzer, QueryParser queryParser) {
        this.directory = directory;
        this.analyzer = analyzer;
        this.queryParser = queryParser;
    }

    @PostConstruct
    public void init() {
        log.info("Ініціалізація LuceneSearchService...");

        // Перевіряємо та видаляємо несумісний індекс
        try {
            Path indexPath = Paths.get(System.getProperty("user.home"), ".myhomelibcorp", "search-index");
            if (Files.exists(indexPath)) {
                // Перевіряємо, чи є файли індексу
                try (var stream = Files.list(indexPath)) {
                    boolean hasIndexFiles = stream.anyMatch(p -> p.getFileName().toString().startsWith("_"));
                    if (hasIndexFiles) {
                        log.info("Виявлено існуючий індекс, спроба відкриття...");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Помилка перевірки індексу: {}", e.getMessage());
        }

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
                // Несумісна версія індексу - видаляємо і створюємо новий
                log.warn("Несумісна версія індексу Lucene: {}", e.getMessage());
                log.info("Видалення старого індексу та створення нового...");
                try {
                    // Закриваємо директорію
                    directory.close();
                    // Видаляємо папку індексу
                    Path indexPath = Paths.get(System.getProperty("user.home"), ".myhomelibcorp", "search-index");
                    deleteDirectory(indexPath);
                    // Створюємо нову директорію
                    Files.createDirectories(indexPath);
                    // Створюємо новий IndexWriter
                    this.indexWriter = new IndexWriter(directory, config);
                    log.info("✅ Новий індекс створено");
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
            Document doc = createDocument(snapshot);
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
        try {
            List<Document> docs = new ArrayList<>();
            for (Book book : books) {
                docs.add(createDocument(BookSnapshot.fromBook(book)));
            }
            indexWriter.addDocuments(docs);
            indexedSinceLastCommit += docs.size();
            if (indexedSinceLastCommit >= commitInterval) {
                commit();
            }
            log.info("Проіндексовано {} книг", docs.size());
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
    public void rebuildIndex() {
        if (isClosed.get()) return;
        log.info("Початок перебудови індексу...");
        try {
            indexWriter.deleteAll();
            commit();
            log.info("Індекс очищено");
        } catch (IOException e) {
            log.error("Помилка очищення індексу", e);
        }
    }

    @Override
    public int getDocumentCount() {
        if (isClosed.get()) return 0;
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
        if (queryText == null || queryText.isBlank() || isClosed.get()) {
            return List.of();
        }
        try {
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                String escapedQuery = QueryParser.escape(queryText.trim().toLowerCase());
                Query query = queryParser.parse(escapedQuery);
                ScoreDoc[] hits = searcher.search(query, limit).scoreDocs;
                List<String> ids = new ArrayList<>(hits.length);
                for (ScoreDoc hit : hits) {
                    Document doc = searcher.doc(hit.doc);
                    String id = doc.get("id");
                    if (id != null && !id.isEmpty()) {
                        ids.add(id);
                    }
                }
                log.debug("Знайдено {} ID для запиту '{}'", ids.size(), queryText);
                return ids;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (Exception e) {
            log.error("Помилка пошуку: {}", queryText, e);
            return List.of();
        }
    }

    @Override
    public SearchResult search(SearchRequest request) {
        if (request == null || request.text() == null || request.text().isBlank() || isClosed.get()) {
            return SearchResult.empty();
        }
        long start = System.currentTimeMillis();
        List<BookId> bookIds = new ArrayList<>();

        try {
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                String text = request.text().trim();
                String escapedText = QueryParser.escape(text);
                Query query = switch (request.mode()) {
                    case EXACT -> queryParser.parse("\"" + escapedText + "\"");
                    case PREFIX -> queryParser.parse(escapedText + "*");
                    case FUZZY -> queryParser.parse(escapedText + "~");
                    default -> queryParser.parse(escapedText);
                };

                ScoreDoc[] hits = searcher.search(query, request.limit()).scoreDocs;
                for (ScoreDoc hit : hits) {
                    Document doc = searcher.doc(hit.doc);
                    String id = doc.get("id");
                    if (id != null && !id.isEmpty()) {
                        bookIds.add(BookId.fromString(id));
                    }
                }

                long elapsed = System.currentTimeMillis() - start;
                log.debug("Пошук '{}' знайшов {} результатів за {} мс", text, bookIds.size(), elapsed);
                return new SearchResult(
                        bookIds,
                        bookIds.size(),
                        request.offset() / request.limit(),
                        request.limit(),
                        elapsed
                );

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

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

    private Document createDocument(BookSnapshot snapshot) {
        Document doc = new Document();
        doc.add(new StringField("id", snapshot.getId().asString(), Field.Store.YES));
        doc.add(new TextField("title", snapshot.getTitle() != null ? snapshot.getTitle() : "", Field.Store.YES));
        doc.add(new TextField("authors", snapshot.getAuthorsText() != null ? snapshot.getAuthorsText() : "", Field.Store.YES));
        doc.add(new TextField("series", snapshot.getSeries() != null ? snapshot.getSeries() : "", Field.Store.YES));
        doc.add(new TextField("genres", snapshot.getGenresText() != null ? snapshot.getGenresText() : "", Field.Store.YES));
        doc.add(new TextField("keywords", snapshot.getKeywords() != null ? snapshot.getKeywords() : "", Field.Store.YES));
        doc.add(new TextField("annotation", snapshot.getAnnotation() != null ? snapshot.getAnnotation() : "", Field.Store.YES));
        return doc;
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