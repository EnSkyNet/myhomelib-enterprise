package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.customfield.CustomFieldType;
import com.myhomelibcorp.domain.model.customfield.CustomFieldValue;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookListRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookArtifactHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.KeywordIndexSupport;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteDateTimeCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Repository
@Slf4j
public class SqliteBookQueryRepository implements BookQueryRepository {

    private static final int STREAM_PAGE_SIZE = 400;
    private static final int SEARCH_STREAM_PAGE_SIZE = 10_000;
    private final CollectionManager collectionManager;
    private final BookRowMapper bookRowMapper;
    private final BookListRowMapper bookListRowMapper;
    private final BookAuthorHelper bookAuthorHelper;
    private final BookGenreHelper bookGenreHelper;
    private final BookArtifactHelper bookArtifactHelper;
    private final BookQueryBuilder queryBuilder;

    @Autowired
    public SqliteBookQueryRepository(CollectionManager collectionManager,
                                     BookRowMapper bookRowMapper,
                                     BookListRowMapper bookListRowMapper,
                                     BookAuthorHelper bookAuthorHelper,
                                     BookGenreHelper bookGenreHelper,
                                     BookArtifactHelper bookArtifactHelper,
                                     BookQueryBuilder queryBuilder) {
        this.collectionManager = collectionManager;
        this.bookRowMapper = bookRowMapper;
        this.bookListRowMapper = bookListRowMapper;
        this.bookAuthorHelper = bookAuthorHelper;
        this.bookGenreHelper = bookGenreHelper;
        this.bookArtifactHelper = bookArtifactHelper;
        this.queryBuilder = queryBuilder;
    }

    /**
     * Backward-compatible constructor for focused repository/performance tests that
     * do not need multi-artifact hydration. Production wiring uses the @Autowired
     * constructor above.
     */
    public SqliteBookQueryRepository(CollectionManager collectionManager,
                                     BookRowMapper bookRowMapper,
                                     BookListRowMapper bookListRowMapper,
                                     BookAuthorHelper bookAuthorHelper,
                                     BookGenreHelper bookGenreHelper,
                                     BookQueryBuilder queryBuilder) {
        this(collectionManager, bookRowMapper, bookListRowMapper, bookAuthorHelper, bookGenreHelper, null, queryBuilder);
    }

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private List<Book> enrichBooks(List<Book> books) {
        return enrichBooks(getJdbcTemplate(), books);
    }

    private List<Book> enrichBooks(JdbcTemplate jdbcTemplate, List<Book> books) {
        if (books.isEmpty()) return books;
        if (bookAuthorHelper != null) bookAuthorHelper.loadAuthorsForBooks(jdbcTemplate, books);
        if (bookGenreHelper != null) bookGenreHelper.loadGenresForBooks(jdbcTemplate, books);
        return bookArtifactHelper != null ? bookArtifactHelper.attachArtifacts(jdbcTemplate, books) : books;
    }

    // ===== Пошук з пагінацією =====

    @Override
    public PageResult<Book> findPage(BookQuery query) {
        return findPage(getJdbcTemplate(), query);
    }

    /** Executes the same bounded query against an explicit collection snapshot connection. */
    public PageResult<Book> findPage(JdbcTemplate jdbcTemplate, BookQuery query) {
        return loadOffsetPage(jdbcTemplate, query, count(jdbcTemplate, query));
    }

    @Override
    public PageResult<Book> findPage(BookQuery query, long knownTotal) {
        if (knownTotal < 0) throw new IllegalArgumentException("knownTotal cannot be negative");
        return loadOffsetPage(getJdbcTemplate(), query, knownTotal);
    }

    private PageResult<Book> loadOffsetPage(JdbcTemplate jdbcTemplate, BookQuery query, long total) {
        var sqlQuery = queryBuilder.build(query);
        List<Book> books = jdbcTemplate.query(sqlQuery.sql(), bookListRowMapper, sqlQuery.params());
        books = enrichBooks(jdbcTemplate, books);
        return toPageResult(query, books, total);
    }

    @Override
    public PageResult<Book> findTitlePageByCursor(BookQuery query, BookPageCursor cursor,
                                                  BookPageDirection pageDirection, long knownTotal) {
        return findTitlePageByCursor(getJdbcTemplate(), query, cursor, pageDirection, knownTotal);
    }

    /** Executes a TITLE keyset continuation against an explicit collection snapshot connection. */
    public PageResult<Book> findTitlePageByCursor(JdbcTemplate jdbcTemplate, BookQuery query,
                                                  BookPageCursor cursor, BookPageDirection pageDirection,
                                                  long knownTotal) {
        if (knownTotal < 0) throw new IllegalArgumentException("knownTotal cannot be negative");
        var sqlQuery = queryBuilder.buildTitleCursor(query, cursor, pageDirection);
        List<Book> books = jdbcTemplate.query(sqlQuery.sql(), bookListRowMapper, sqlQuery.params());
        if (pageDirection == BookPageDirection.BEFORE && books.size() > 1) {
            Collections.reverse(books);
        }
        books = enrichBooks(jdbcTemplate, books);
        return toPageResult(query, books, knownTotal);
    }

    private static PageResult<Book> toPageResult(BookQuery query, List<Book> books, long total) {
        int size = Math.max(1, query.pagination().limit());
        int page = query.pagination().offset() / size;
        int totalPages = (int) Math.ceil((double) total / size);
        return new PageResult<>(books, total, totalPages, page, size);
    }

    @Override
    public long count(BookQuery query) {
        return count(getJdbcTemplate(), query);
    }

    /** Counts against an explicit collection snapshot connection. */
    public long count(JdbcTemplate jdbcTemplate, BookQuery query) {
        var sqlQuery = queryBuilder.buildCount(query);
        Long result = jdbcTemplate.queryForObject(sqlQuery.sql(), Long.class, sqlQuery.params());
        return result != null ? result : 0L;
    }

    // ===== Пошук по ID =====

    @Override
    public Optional<Book> findById(BookId id) {
        String sql = "SELECT * FROM books WHERE id = ?";
        try {
            Book book = getJdbcTemplate().queryForObject(sql, bookRowMapper, id.asString());
            book = enrichBooks(List.of(book)).getFirst();
            return Optional.of(book);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Book> findByIds(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Book> loadedBooks = new ArrayList<>(Math.min(ids.size(), SqliteInClauseSupport.MAX_ITEMS));
        SqliteInClauseSupport.forEachChunk(ids, part -> {
            String sql = "SELECT * FROM books WHERE id IN (" + SqliteInClauseSupport.placeholders(part.size()) + ")";
            Object[] params = part.stream().map(BookId::asString).toArray();
            loadedBooks.addAll(getJdbcTemplate().query(sql, bookRowMapper, params));
        });
        return enrichBooks(loadedBooks);
    }

    @Override
    public List<Book> findListItemsByIds(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Book> loadedBooks = new ArrayList<>(Math.min(ids.size(), SqliteInClauseSupport.MAX_ITEMS));
        SqliteInClauseSupport.forEachChunk(ids, part -> {
            String sql = "SELECT " + BookQueryBuilder.BOOK_LIST_PROJECTION
                    + " FROM books b WHERE b.id IN (" + SqliteInClauseSupport.placeholders(part.size()) + ")";
            Object[] params = part.stream().map(BookId::asString).toArray();
            loadedBooks.addAll(getJdbcTemplate().query(sql, bookListRowMapper, params));
        });
        return enrichBooks(loadedBooks);
    }

    @Override
    public Optional<Book> findByStorage(String collectionRoot, String folder, String fileName, String archiveEntry) {
        String sql = """
                SELECT * FROM books
                WHERE COALESCE(collection_root, '') = ?
                  AND COALESCE(folder, '') = ?
                  AND COALESCE(file_name, '') = ?
                  AND COALESCE(archive_entry, '') = ?
                LIMIT 1
                """;
        try {
            Book book = getJdbcTemplate().queryForObject(sql, bookRowMapper,
                    safe(collectionRoot), safe(folder), safe(fileName), safe(archiveEntry));
            book = enrichBooks(List.of(book)).getFirst();
            return Optional.of(book);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Book> findByArchiveContainer(String collectionRoot, String relativeArchivePath, String absoluteArchivePath) {
        String rel = normalizePath(relativeArchivePath);
        String abs = normalizePath(absoluteArchivePath);
        String root = normalizePath(collectionRoot);
        String sql = """
                SELECT * FROM books
                WHERE COALESCE(archive_entry, '') <> ''
                  AND (
                       lower(replace(COALESCE(folder, ''), '\\', '/')) = lower(?)
                    OR lower(replace(COALESCE(folder, ''), '\\', '/')) = lower(?)
                    OR (lower(replace(COALESCE(collection_root, ''), '\\', '/')) = lower(?)
                        AND lower(replace(COALESCE(folder, ''), '\\', '/')) = lower(?))
                  )
                """;
        List<Book> books = getJdbcTemplate().query(sql, bookRowMapper, rel, abs, root, rel);
        books = enrichBooks(books);
        return books;
    }

    @Override
    public Stream<Book> streamAll() {
        return findAllStreaming();
    }

    /**
     * Bounded V33 candidate generation for large catalogs. Strong signals are collected first
     * (series, authors, genres), then deterministic weak-signal fallbacks are added only while
     * capacity remains. Exact scoring stays in BookSimilarityPolicy.
     */
    @Override
    public Stream<Book> streamSimilarityCandidates(Book source, int requestedResults) {
        if (source == null) return Stream.empty();
        int target = Math.max(1, Math.min(50, requestedResults));
        int candidateLimit = Math.max(1_000, Math.min(5_000, target * 100));
        LinkedHashSet<String> ids = new LinkedHashSet<>(candidateLimit * 2);
        String sourceId = source.getId().asString();

        if (source.getSeries() != null && !source.getSeries().isBlank()) {
            addSimilarityCandidateIds(ids, candidateLimit,
                    "SELECT id FROM books WHERE deleted=0 AND id<>? "
                            + "AND series IS NOT NULL AND TRIM(series)<>'' "
                            + "AND LOWER(TRIM(series))=LOWER(TRIM(?)) ORDER BY id LIMIT ?",
                    sourceId, source.getSeries(), candidateLimit);
        }

        List<String> authorIds = source.getAuthors().stream()
                .map(a -> a.getId().asString()).distinct().limit(SqliteInClauseSupport.MAX_ITEMS).toList();
        if (!authorIds.isEmpty() && ids.size() < candidateLimit) {
            int remaining = candidateLimit - ids.size();
            String sql = "SELECT b.id FROM books b JOIN book_authors ba ON ba.book_id=b.id "
                    + "WHERE b.deleted=0 AND b.id<>? AND ba.author_id IN ("
                    + SqliteInClauseSupport.placeholders(authorIds.size()) + ") "
                    + "GROUP BY b.id ORDER BY COUNT(DISTINCT ba.author_id) DESC, b.id LIMIT ?";
            List<Object> params = new ArrayList<>(authorIds.size() + 2);
            params.add(sourceId); params.addAll(authorIds); params.add(remaining);
            addSimilarityCandidateIds(ids, candidateLimit, sql, params.toArray());
        }

        List<String> genreIds = source.getGenres().stream()
                .map(g -> g.getId().asString()).distinct().limit(SqliteInClauseSupport.MAX_ITEMS).toList();
        if (!genreIds.isEmpty() && ids.size() < candidateLimit) {
            int remaining = candidateLimit - ids.size();
            String sql = "SELECT b.id FROM books b JOIN book_genres bg ON bg.book_id=b.id "
                    + "WHERE b.deleted=0 AND b.id<>? AND bg.genre_code IN ("
                    + SqliteInClauseSupport.placeholders(genreIds.size()) + ") "
                    + "GROUP BY b.id ORDER BY COUNT(DISTINCT bg.genre_code) DESC, b.id LIMIT ?";
            List<Object> params = new ArrayList<>(genreIds.size() + 2);
            params.add(sourceId); params.addAll(genreIds); params.add(remaining);
            addSimilarityCandidateIds(ids, candidateLimit, sql, params.toArray());
        }

        List<String> keywordNames = KeywordIndexSupport.tokenize(source.getKeywords()).stream()
                .map(KeywordIndexSupport.KeywordToken::normalizedName)
                .distinct().limit(SqliteInClauseSupport.MAX_ITEMS).toList();
        if (!keywordNames.isEmpty() && ids.size() < candidateLimit) {
            int remaining = candidateLimit - ids.size();
            String sql = "SELECT b.id FROM books b JOIN keyword_books kb ON kb.book_id=b.id "
                    + "WHERE b.deleted=0 AND b.id<>? AND kb.normalized_name IN ("
                    + SqliteInClauseSupport.placeholders(keywordNames.size()) + ") "
                    + "GROUP BY b.id ORDER BY COUNT(DISTINCT kb.normalized_name) DESC, b.id LIMIT ?";
            List<Object> params = new ArrayList<>(keywordNames.size() + 2);
            params.add(sourceId); params.addAll(keywordNames); params.add(remaining);
            addSimilarityCandidateIds(ids, candidateLimit, sql, params.toArray());
        }

        String language = source.getLanguage() == null ? "und" : source.getLanguage().value();
        if (!"und".equals(language) && ids.size() < candidateLimit) {
            int remaining = candidateLimit - ids.size();
            if (source.getRate() > 0) {
                addSimilarityCandidateIds(ids, candidateLimit,
                        "SELECT id FROM books WHERE deleted=0 AND id<>? AND language=? "
                                + "ORDER BY ABS(rate-?), id LIMIT ?",
                        sourceId, language, source.getRate(), remaining);
            } else {
                addSimilarityCandidateIds(ids, candidateLimit,
                        "SELECT id FROM books WHERE deleted=0 AND id<>? AND language=? ORDER BY id LIMIT ?",
                        sourceId, language, remaining);
            }
        }

        if (source.getRate() > 0 && ids.size() < candidateLimit) {
            int remaining = candidateLimit - ids.size();
            addSimilarityCandidateIds(ids, candidateLimit,
                    "SELECT id FROM books WHERE deleted=0 AND id<>? AND rate>0 "
                            + "ORDER BY ABS(rate-?), id LIMIT ?",
                    sourceId, source.getRate(), remaining);
        }

        if (ids.isEmpty()) return Stream.empty();
        List<BookId> candidateIds = ids.stream().limit(candidateLimit).map(BookId::fromString).toList();
        return findByIds(candidateIds).stream();
    }

    private void addSimilarityCandidateIds(LinkedHashSet<String> ids, int maxCandidates,
                                           String sql, Object... params) {
        if (ids.size() >= maxCandidates) return;
        for (String id : getJdbcTemplate().queryForList(sql, String.class, params)) {
            if (id != null && !id.isBlank()) ids.add(id);
            if (ids.size() >= maxCandidates) break;
        }
    }

    /**
     * Dedicated Lucene projection. A 700k-row rebuild previously materialized full Book,
     * BookMetadata and BookFile aggregates in 400-row pages and then discarded most fields.
     * This path reads only searchable columns, skips tombstones in SQL and enriches relations
     * with bounded range scans per page.
     */
    @Override
    public Stream<BookSnapshot> streamSearchSnapshots() {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        new SearchSnapshotIterator(SEARCH_STREAM_PAGE_SIZE),
                        Spliterator.ORDERED | Spliterator.NONNULL
                ),
                false
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizePath(String value) {
        return safe(value).replace('\\', '/');
    }

    // ===== Спеціальні запити =====

    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        String sql = """
            SELECT b.* FROM books b
            JOIN book_authors ba ON b.id = ba.book_id
            JOIN authors a ON ba.author_id = a.id
            WHERE b.title = ? AND a.last_name = ? AND b.deleted = 0
            LIMIT 1
            """;
        try {
            Book book = getJdbcTemplate().queryForObject(sql, bookRowMapper, title, authorLastName);
            book = enrichBooks(List.of(book)).getFirst();
            return Optional.of(book);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Book> findRecent(int limit) {
        String sql = "SELECT " + BookQueryBuilder.BOOK_LIST_PROJECTION + " FROM books b "
                + "WHERE b.deleted = 0 ORDER BY b.update_date DESC LIMIT ?";
        List<Book> books = getJdbcTemplate().query(sql, bookListRowMapper, limit);
        books = enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findRecentlyAdded(int limit) {
        String sql = "SELECT " + BookQueryBuilder.BOOK_LIST_PROJECTION + " FROM books b "
                + "WHERE b.deleted = 0 ORDER BY b.created_at DESC LIMIT ?";
        List<Book> books = getJdbcTemplate().query(sql, bookListRowMapper, limit);
        books = enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findFavoriteAuthors(int limit) {
        String sql = "SELECT " + BookQueryBuilder.BOOK_LIST_PROJECTION + " FROM books b "
                + "WHERE b.deleted = 0 ORDER BY b.rate DESC LIMIT ?";
        List<Book> books = getJdbcTemplate().query(sql, bookListRowMapper, limit);
        books = enrichBooks(books);
        return books;
    }


    // ===== Streaming =====

    /**
     * Memory-bounded keyset traversal. Unlike OFFSET pagination this keeps
     * query cost stable as the catalogue grows and avoids COUNT(*) per page.
     * The batch is deliberately <= common SQLite bind-variable limits because
     * author/genre enrichment uses one IN (...) query per relation table.
     */
    public Stream<Book> findAllStreaming() {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        new StreamingBookIterator(STREAM_PAGE_SIZE),
                        Spliterator.ORDERED | Spliterator.NONNULL
                ),
                false
        );
    }

    private class StreamingBookIterator implements java.util.Iterator<Book> {
        private final int pageSize;
        private String lastId = "";
        private List<Book> currentPage = List.of();
        private int currentIndex;
        private boolean endReached;

        private StreamingBookIterator(int pageSize) {
            this.pageSize = Math.max(1, Math.min(400, pageSize));
            loadNextPage();
        }

        @Override
        public boolean hasNext() {
            if (currentIndex < currentPage.size()) return true;
            if (endReached) return false;
            loadNextPage();
            return currentIndex < currentPage.size();
        }

        @Override
        public Book next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            return currentPage.get(currentIndex++);
        }

        private void loadNextPage() {
            String sql = "SELECT * FROM books WHERE id > ? ORDER BY id LIMIT ?";
            currentPage = getJdbcTemplate().query(sql, bookRowMapper, lastId, pageSize);
            currentPage = enrichBooks(currentPage);
            currentIndex = 0;
            if (currentPage.isEmpty()) {
                endReached = true;
                return;
            }
            lastId = currentPage.get(currentPage.size() - 1).getId().asString();
            endReached = currentPage.size() < pageSize;
        }
    }

    private class SearchSnapshotIterator implements Iterator<BookSnapshot> {
        private final int pageSize;
        private String lastId = "";
        private List<BookSnapshot> currentPage = List.of();
        private int currentIndex;
        private boolean endReached;
        private Boolean customFieldsPresent;
        private Boolean annotationsPresent;

        private SearchSnapshotIterator(int pageSize) {
            this.pageSize = Math.max(100, Math.min(10_000, pageSize));
        }

        @Override
        public boolean hasNext() {
            if (currentIndex < currentPage.size()) return true;
            if (endReached) return false;
            loadNextPage();
            return currentIndex < currentPage.size();
        }

        @Override
        public BookSnapshot next() {
            if (!hasNext()) throw new NoSuchElementException();
            return currentPage.get(currentIndex++);
        }

        private void loadNextPage() {
            String sql = """
                    SELECT id, title, series, keywords, annotation, file_name, language,
                           rate, progress, year, publisher, lib_id, library_rate, translators,
                           city, source_url, isbn, created_at, local
                      FROM books
                     WHERE deleted = 0 AND id > ?
                     ORDER BY id
                     LIMIT ?
                    """;
            List<SearchBaseRow> baseRows = getJdbcTemplate().query(sql, (rs, rowNum) -> {
                Integer year = rs.getInt("year");
                if (rs.wasNull()) year = null;
                return new SearchBaseRow(
                        rs.getString("id"), value(rs.getString("title")), value(rs.getString("series")),
                        value(rs.getString("keywords")), value(rs.getString("annotation")),
                        value(rs.getString("file_name")), value(rs.getString("language")),
                        rs.getInt("rate"), rs.getInt("progress"), year,
                        value(rs.getString("publisher")), value(rs.getString("lib_id")),
                        rs.getInt("library_rate"), value(rs.getString("translators")),
                        value(rs.getString("city")), value(rs.getString("source_url")),
                        value(rs.getString("isbn")), SqliteDateTimeCodec.parse(rs.getString("created_at")),
                        rs.getInt("local") == 1);
            }, lastId, pageSize);

            currentIndex = 0;
            if (baseRows.isEmpty()) {
                currentPage = List.of();
                endReached = true;
                return;
            }

            String first = baseRows.getFirst().id();
            String last = baseRows.getLast().id();
            Set<String> pageIds = new HashSet<>(baseRows.size() * 2);
            for (SearchBaseRow row : baseRows) pageIds.add(row.id());

            Map<String, RelatedText> authors = loadSearchAuthors(first, last, pageIds);
            Map<String, RelatedText> genres = loadSearchGenres(first, last, pageIds);
            if (customFieldsPresent == null) customFieldsPresent = hasAnyCustomFieldValues();
            if (annotationsPresent == null) annotationsPresent = hasAnyAnnotations();
            Map<String, List<CustomFieldValue>> customFields = customFieldsPresent
                    ? loadSearchCustomFields(first, last, pageIds) : Map.of();
            Map<String, AnnotationCounts> annotationCounts = annotationsPresent
                    ? loadSearchAnnotationCounts(first, last, pageIds) : Map.of();
            List<BookSnapshot> snapshots = new ArrayList<>(baseRows.size());
            for (SearchBaseRow row : baseRows) {
                RelatedText author = authors.get(row.id());
                RelatedText genre = genres.get(row.id());
                AnnotationCounts activity = annotationCounts.getOrDefault(row.id(), AnnotationCounts.EMPTY);
                snapshots.add(BookSnapshot.builder()
                        .id(BookId.fromString(row.id()))
                        .title(row.title())
                        .authorsText(author == null ? "" : author.text())
                        .authorIds(author == null ? "" : author.ids())
                        .series(row.series())
                        .genresText(genre == null ? "" : genre.text())
                        .genreIds(genre == null ? "" : genre.ids())
                        .keywords(row.keywords())
                        .annotation(row.annotation())
                        .fileName(row.fileName())
                        .language(row.language())
                        .rate(row.rate())
                        .progress(row.progress())
                        .year(row.year())
                        .publisher(row.publisher())
                        .libId(row.libId())
                        .libraryRate(row.libraryRate())
                        .translators(row.translators())
                        .city(row.city())
                        .sourceUrl(row.sourceUrl())
                        .isbn(row.isbn())
                        .createdAt(row.createdAt())
                        .deleted(false)
                        .local(row.local())
                        .noteCount(activity.notes())
                        .highlightCount(activity.highlights())
                        .customFieldValues(customFields.getOrDefault(row.id(), List.of()))
                        .build());
            }
            currentPage = snapshots;
            lastId = last;
            endReached = baseRows.size() < pageSize;
        }
    }


    private boolean hasAnyCustomFieldValues() {
        Integer present = getJdbcTemplate().queryForObject(
                "SELECT EXISTS(SELECT 1 FROM custom_field_values LIMIT 1)", Integer.class);
        return present != null && present != 0;
    }

    private boolean hasAnyAnnotations() {
        Integer present = getJdbcTemplate().queryForObject(
                "SELECT EXISTS(SELECT 1 FROM annotations LIMIT 1)", Integer.class);
        return present != null && present != 0;
    }

    private Map<String, AnnotationCounts> loadSearchAnnotationCounts(String firstId, String lastId, Set<String> pageIds) {
        Map<String, AnnotationCounts> result = new HashMap<>();
        String sql = """
                SELECT book_id,
                       SUM(CASE WHEN annotation_type='NOTE' THEN 1 ELSE 0 END) AS notes,
                       SUM(CASE WHEN annotation_type='HIGHLIGHT' THEN 1 ELSE 0 END) AS highlights
                  FROM annotations
                 WHERE book_id >= ? AND book_id <= ?
                 GROUP BY book_id
                 ORDER BY book_id
                """;
        getJdbcTemplate().query(sql, rs -> {
            String bookId = rs.getString("book_id");
            if (!pageIds.contains(bookId)) return;
            result.put(bookId, new AnnotationCounts(rs.getInt("notes"), rs.getInt("highlights")));
        }, firstId, lastId);
        return result;
    }

    private Map<String, RelatedText> loadSearchAuthors(String firstId, String lastId, Set<String> pageIds) {
        Map<String, RelatedText> result = new HashMap<>();
        String sql = """
                SELECT ba.book_id, a.id, a.first_name, a.middle_name, a.last_name
                  FROM book_authors ba
                  JOIN authors a ON a.id = ba.author_id
                 WHERE ba.book_id >= ? AND ba.book_id <= ?
                 ORDER BY ba.book_id, ba.author_id
                """;
        getJdbcTemplate().query(sql, rs -> {
            String bookId = rs.getString("book_id");
            if (!pageIds.contains(bookId)) return;
            String fullName = joinName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name"));
            if (Book.isLegacyUnknownAuthorPlaceholder(fullName)) return;
            result.computeIfAbsent(bookId, ignored -> new RelatedText(", ", " "))
                    .add(fullName, rs.getString("id"));
        }, firstId, lastId);
        return result;
    }

    private Map<String, RelatedText> loadSearchGenres(String firstId, String lastId, Set<String> pageIds) {
        Map<String, RelatedText> result = new HashMap<>();
        String sql = """
                SELECT bg.book_id, g.code, g.name
                  FROM book_genres bg
                  JOIN genres g ON g.code = bg.genre_code
                 WHERE bg.book_id >= ? AND bg.book_id <= ?
                 ORDER BY bg.book_id, bg.genre_code
                """;
        getJdbcTemplate().query(sql, rs -> {
            String bookId = rs.getString("book_id");
            if (!pageIds.contains(bookId)) return;
            result.computeIfAbsent(bookId, ignored -> new RelatedText(", ", " "))
                    .add(value(rs.getString("name")), rs.getString("code"));
        }, firstId, lastId);
        return result;
    }

    private Map<String, List<CustomFieldValue>> loadSearchCustomFields(String firstId, String lastId, Set<String> pageIds) {
        Map<String, List<CustomFieldValue>> result = new HashMap<>();
        String sql = """
                SELECT v.book_id, v.definition_id, d.field_type, v.value_text
                  FROM custom_field_values v
                  JOIN custom_field_definitions d ON d.id = v.definition_id
                 WHERE v.book_id >= ? AND v.book_id <= ?
                 ORDER BY v.book_id, v.definition_id
                """;
        getJdbcTemplate().query(sql, rs -> {
            String bookId = rs.getString("book_id");
            if (!pageIds.contains(bookId)) return;
            long definitionId = rs.getLong("definition_id");
            CustomFieldType type = CustomFieldType.valueOf(rs.getString("field_type"));
            result.computeIfAbsent(bookId, ignored -> new ArrayList<>())
                    .add(new CustomFieldValue(definitionId, type, value(rs.getString("value_text"))));
        }, firstId, lastId);
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return result;
    }

    private static String joinName(String last, String first, String middle) {
        StringBuilder out = new StringBuilder();
        appendNamePart(out, last);
        appendNamePart(out, first);
        appendNamePart(out, middle);
        return out.toString();
    }

    private static void appendNamePart(StringBuilder out, String value) {
        if (value == null || value.isBlank()) return;
        if (!out.isEmpty()) out.append(' ');
        out.append(value);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }


    private record AnnotationCounts(int notes, int highlights) {
        private static final AnnotationCounts EMPTY = new AnnotationCounts(0, 0);
    }

    private record SearchBaseRow(
            String id, String title, String series, String keywords, String annotation, String fileName,
            String language, Integer rate, Integer progress, Integer year, String publisher, String libId,
            Integer libraryRate, String translators, String city, String sourceUrl, String isbn,
            LocalDateTime createdAt, boolean local) { }

    private static class RelatedText {
        private final String textSeparator;
        private final String idSeparator;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder ids = new StringBuilder();

        private RelatedText(String textSeparator, String idSeparator) {
            this.textSeparator = textSeparator;
            this.idSeparator = idSeparator;
        }

        private RelatedText add(String label, String id) {
            if (label != null && !label.isBlank()) {
                if (!text.isEmpty()) text.append(textSeparator);
                text.append(label);
            }
            if (id != null && !id.isBlank()) {
                if (!ids.isEmpty()) ids.append(idSeparator);
                ids.append(id);
            }
            return this;
        }

        private String text() { return text.toString(); }
        private String ids() { return ids.toString(); }
    }

}