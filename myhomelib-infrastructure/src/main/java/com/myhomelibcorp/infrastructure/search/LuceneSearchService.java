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
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class LuceneSearchService implements SearchIndexer, SearchQueryService, IndexRebuilder {

    private Directory directory;
    private final Analyzer analyzer;
    private final QueryParser queryParser;
    private final BookQueryRepository bookQueryRepository;

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
        this.queryParser = queryParser;
        this.bookQueryRepository = bookQueryRepository;
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
        int indexed = 0;
        try {
            for (Book book : books) {
                if (book == null) continue;
                BookSnapshot snapshot = BookSnapshot.fromBook(book);
                indexWriter.updateDocument(new Term("id", snapshot.getId().asString()), createDocument(snapshot));
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
                    indexWriter.updateDocument(new Term("id", snapshot.getId().asString()), createDocument(snapshot));
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
        if (queryText == null || queryText.isBlank() || isClosed.get()) return List.of();
        SearchResult result = search(SearchRequest.builder().text(queryText).limit(limit).build());
        return result.bookIds().stream().map(BookId::asString).toList();
    }

    @Override
    public SearchResult search(SearchRequest request) {
        if (request == null || isClosed.get()) return SearchResult.empty();
        long start = System.currentTimeMillis();
        List<BookId> bookIds = new ArrayList<>();

        try {
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                BooleanQuery.Builder b = new BooleanQuery.Builder();
                String text = request.text() == null ? "" : request.text().trim();
                if (!text.isBlank()) {
                    b.add(parseTextQuery(text, request.mode()), BooleanClause.Occur.MUST);
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

    /**
     * Accepts both Lucene field syntax and the classic MyHomeLib conventions:
     * %text% -> wildcard, ="text" -> exact phrase, OR -> alternatives.
     */
    private Query parseTextQuery(String raw, com.myhomelibcorp.application.query.search.SearchMode mode) throws Exception {
        String text = normalizeClassicSearchSyntax(raw);
        boolean hasSyntax = text.contains(":") || text.contains(" OR ") || text.contains("*") || text.contains("?")
                || text.startsWith("\"") || text.contains(" AND ") || text.contains(" NOT ");
        if (!hasSyntax) {
            String escaped = QueryParser.escape(text);
            return switch (mode) {
                case EXACT -> queryParser.parse("\"" + escaped + "\"");
                case PREFIX -> queryParser.parse(escaped + "*");
                case FUZZY -> queryParser.parse(escaped + "~");
                default -> queryParser.parse(escaped);
            };
        }
        try {
            return queryParser.parse(text);
        } catch (Exception syntaxError) {
            return queryParser.parse(QueryParser.escape(raw));
        }
    }

    private String normalizeClassicSearchSyntax(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return s;

        // Classic MyHomeLib field aliases (English + Ukrainian).
        s = s.replaceAll("(?i)\\bauthor:", "authors:")
                .replaceAll("(?iU)\\bавтор:", "authors:")
                .replaceAll("(?i)\\bgenre:", "genres:")
                .replaceAll("(?iU)\\bжанр:", "genres:")
                .replaceAll("(?i)\\bfile(name)?:", "file_name:")
                .replaceAll("(?iU)\\bфайл:", "file_name:")
                .replaceAll("(?i)\\blang(uage)?:", "language:")
                .replaceAll("(?iU)\\bмова:", "language:")
                .replaceAll("(?iU)\\bназва:", "title:")
                .replaceAll("(?iU)\\bсерія:", "series:")
                .replaceAll("(?iU)\\bанотація:", "annotation:")
                .replaceAll("(?iU)\\bключові(?:слова)?:", "keywords:")
                .replaceAll("(?i)\\bpub(lisher)?:", "publisher:")
                .replaceAll("(?iU)\\bвидавець:", "publisher:")
                .replaceAll("(?iU)\\bвидавництво:", "publisher:")
                .replaceAll("(?i)\\blib(rate|raryrate):", "library_rate:")
                .replaceAll("(?iU)\\bрейтингбібліотеки:", "library_rate:")
                .replaceAll("(?i)\\buser(rate|rating):", "rate:")
                .replaceAll("(?iU)\\bмійрейтинг:", "rate:")
                .replaceAll("(?i)\\blibid:", "lib_id:")
                .replaceAll("(?iU)\\bперекладачі?:", "translators:")
                .replaceAll("(?iU)\\bмісто:", "city:")
                .replaceAll("(?i)\\badded:", "created:")
                .replaceAll("(?i)\\bdateadded:", "created:")
                .replaceAll("(?iU)\\bдодано:", "created:");

        // Comparison aliases must be normalized before the generic comparison parser.
        s = s.replaceAll("(?i)\\b(?:added|dateadded)\\s*(<>|>=|<=|>|<|=)", "created$1")
                .replaceAll("(?iU)\\bдодано\\s*(<>|>=|<=|>|<|=)", "created$1");

        // Accept comparisons both as year:>2020 and year>2020.
        s = s.replaceAll("(?i)\\b(year|created|library_rate|rate)\\s*(<>|>=|<=|>|<|=)\\s*([^\\s)]+)", "$1:$2$3");
        s = normalizeComparison(s, "<>", true, true);
        s = normalizeComparison(s, ">=", true, false);
        s = normalizeComparison(s, "<=", false, true);
        s = normalizeComparison(s, ">", true, false);
        s = normalizeComparison(s, "<", false, true);

        // Classic exact-value syntax: ="text" and field:="text".
        s = s.replaceAll("(?i)(\\b[a-z_]+:)\\s*=\\s*\\\"([^\\\"]+)\\\"", "$1\\\"$2\\\"");
        if (s.startsWith("=\\\"") && s.endsWith("\\\"") && s.length() > 3) s = s.substring(1);

        // Classic contains syntax can appear globally or after a field prefix.
        s = normalizePercentWildcards(s);
        return s;
    }

    private String normalizeComparison(String input, String operator, boolean lowerBound, boolean upperBound) {
        String op = java.util.regex.Pattern.quote(operator);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)\\b(year|created|library_rate|rate):\\s*" + op + "\\s*([^\\s)]+)");
        java.util.regex.Matcher m = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String field = m.group(1);
            String value = normalizeComparableValue(field, m.group(2));
            String replacement;
            if ("<>".equals(operator)) {
                replacement = "(*:* AND NOT " + field + ":" + value + ")";
            } else if (">=".equals(operator)) {
                replacement = field + ":[" + value + " TO *]";
            } else if (">".equals(operator)) {
                replacement = field + ":{" + value + " TO *]";
            } else if ("<=".equals(operator)) {
                replacement = field + ":[* TO " + value + "]";
            } else if ("<".equals(operator)) {
                replacement = field + ":[* TO " + value + "}";
            } else {
                replacement = field + ":" + value;
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String normalizeComparableValue(String field, String value) {
        String v = value == null ? "" : value.trim().replace("\\\"", "");
        if ("created".equalsIgnoreCase(field)) return v.replaceAll("[^0-9]", "");
        if ("year".equalsIgnoreCase(field)) {
            try { return String.format(java.util.Locale.ROOT, "%04d", Integer.parseInt(v)); }
            catch (NumberFormatException ignored) { return QueryParser.escape(v); }
        }
        return QueryParser.escape(v);
    }

    private String normalizePercentWildcards(String input) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([^%]+)%").matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String replacement = "*" + QueryParser.escape(m.group(1)) + "*";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    @Override
    public int getIndexedDocumentCount() {
        return getDocumentCount();
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

    private Document createDocument(BookSnapshot snapshot) {
        Document doc = new Document();
        doc.add(new StringField("id", snapshot.getId().asString(), Field.Store.YES));
        doc.add(new TextField("title", safe(snapshot.getTitle()), Field.Store.YES));
        doc.add(new TextField("authors", safe(snapshot.getAuthorsText()), Field.Store.YES));
        doc.add(new TextField("series", safe(snapshot.getSeries()), Field.Store.YES));
        doc.add(new TextField("genres", safe(snapshot.getGenresText()), Field.Store.YES));
        doc.add(new TextField("keywords", safe(snapshot.getKeywords()), Field.Store.YES));
        doc.add(new TextField("annotation", safe(snapshot.getAnnotation()), Field.Store.YES));
        doc.add(new TextField("file_name", safe(snapshot.getFileName()), Field.Store.YES));
        doc.add(new TextField("publisher", safe(snapshot.getPublisher()), Field.Store.YES));
        doc.add(new TextField("translators", safe(snapshot.getTranslators()), Field.Store.YES));
        doc.add(new TextField("city", safe(snapshot.getCity()), Field.Store.YES));
        doc.add(new StringField("lib_id", safe(snapshot.getLibId()), Field.Store.YES));
        doc.add(new StringField("language", safe(snapshot.getLanguage()).toLowerCase(java.util.Locale.ROOT), Field.Store.YES));
        for (String id : safe(snapshot.getAuthorIds()).split("\\s+")) if (!id.isBlank()) doc.add(new StringField("author_id", id, Field.Store.NO));
        for (String id : safe(snapshot.getGenreIds()).split("\\s+")) if (!id.isBlank()) doc.add(new StringField("genre_id", id, Field.Store.NO));
        int libraryRate = snapshot.getLibraryRate() == null ? 0 : snapshot.getLibraryRate();
        doc.add(new IntPoint("library_rate_num", libraryRate));
        doc.add(new StringField("library_rate", Integer.toString(libraryRate), Field.Store.NO));
        int rate = snapshot.getRate() == null ? 0 : snapshot.getRate();
        doc.add(new IntPoint("rate_num", rate));
        doc.add(new StringField("rate", Integer.toString(rate), Field.Store.NO));
        int year = snapshot.getYear() == null ? 0 : snapshot.getYear();
        doc.add(new IntPoint("year_num", year));
        doc.add(new StringField("year", year <= 0 ? "0000" : String.format(java.util.Locale.ROOT, "%04d", year), Field.Store.NO));
        if (snapshot.getCreatedAt() != null) {
            doc.add(new LongPoint("created_day", snapshot.getCreatedAt().toLocalDate().toEpochDay()));
            doc.add(new StringField("created", snapshot.getCreatedAt().toLocalDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), Field.Store.NO));
        }
        doc.add(new StringField("local", snapshot.isLocal() ? "1" : "0", Field.Store.NO));
        doc.add(new StringField("deleted", snapshot.isDeleted() ? "1" : "0", Field.Store.NO));
        return doc;
    }

    private String safe(String value) { return value == null ? "" : value; }

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
