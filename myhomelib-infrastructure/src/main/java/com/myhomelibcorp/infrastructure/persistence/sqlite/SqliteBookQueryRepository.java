package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookListRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteBookQueryRepository implements BookQueryRepository {

    private static final int STREAM_PAGE_SIZE = 400;
    private final CollectionManager collectionManager;
    private final BookRowMapper bookRowMapper;
    private final BookListRowMapper bookListRowMapper;
    private final BookAuthorHelper bookAuthorHelper;
    private final BookGenreHelper bookGenreHelper;
    private final BookQueryBuilder queryBuilder;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private void enrichBooks(List<Book> books) {
        if (books.isEmpty()) return;
        bookAuthorHelper.loadAuthorsForBooks(books);
        bookGenreHelper.loadGenresForBooks(books);
    }

    // ===== Пошук з пагінацією =====

    @Override
    public PageResult<Book> findPage(BookQuery query) {
        return loadOffsetPage(query, count(query));
    }

    @Override
    public PageResult<Book> findPage(BookQuery query, long knownTotal) {
        if (knownTotal < 0) throw new IllegalArgumentException("knownTotal cannot be negative");
        return loadOffsetPage(query, knownTotal);
    }

    private PageResult<Book> loadOffsetPage(BookQuery query, long total) {
        var sqlQuery = queryBuilder.build(query);
        List<Book> books = getJdbcTemplate().query(sqlQuery.sql(), bookListRowMapper, sqlQuery.params());
        enrichBooks(books);
        return toPageResult(query, books, total);
    }

    @Override
    public PageResult<Book> findTitlePageByCursor(BookQuery query, BookPageCursor cursor,
                                                  BookPageDirection pageDirection, long knownTotal) {
        if (knownTotal < 0) throw new IllegalArgumentException("knownTotal cannot be negative");
        var sqlQuery = queryBuilder.buildTitleCursor(query, cursor, pageDirection);
        List<Book> books = getJdbcTemplate().query(sqlQuery.sql(), bookListRowMapper, sqlQuery.params());
        if (pageDirection == BookPageDirection.BEFORE && books.size() > 1) {
            Collections.reverse(books);
        }
        enrichBooks(books);
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
        var sqlQuery = queryBuilder.buildCount(query);
        Long result = getJdbcTemplate().queryForObject(sqlQuery.sql(), Long.class, sqlQuery.params());
        return result != null ? result : 0L;
    }

    // ===== Пошук по ID =====

    @Override
    public Optional<Book> findById(BookId id) {
        String sql = "SELECT * FROM books WHERE id = ?";
        try {
            Book book = getJdbcTemplate().queryForObject(sql, bookRowMapper, id.asString());
            enrichBooks(List.of(book));
            return Optional.of(book);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Book> findByIds(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Book> books = new ArrayList<>(Math.min(ids.size(), SqliteInClauseSupport.MAX_ITEMS));
        SqliteInClauseSupport.forEachChunk(ids, part -> {
            String sql = "SELECT * FROM books WHERE id IN (" + SqliteInClauseSupport.placeholders(part.size()) + ")";
            Object[] params = part.stream().map(BookId::asString).toArray();
            books.addAll(getJdbcTemplate().query(sql, bookRowMapper, params));
        });
        enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findListItemsByIds(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Book> books = new ArrayList<>(Math.min(ids.size(), SqliteInClauseSupport.MAX_ITEMS));
        SqliteInClauseSupport.forEachChunk(ids, part -> {
            String sql = "SELECT " + BookQueryBuilder.BOOK_LIST_PROJECTION
                    + " FROM books b WHERE b.id IN (" + SqliteInClauseSupport.placeholders(part.size()) + ")";
            Object[] params = part.stream().map(BookId::asString).toArray();
            books.addAll(getJdbcTemplate().query(sql, bookListRowMapper, params));
        });
        enrichBooks(books);
        return books;
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
            enrichBooks(List.of(book));
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
        enrichBooks(books);
        return books;
    }

    @Override
    public Stream<Book> streamAll() {
        return findAllStreaming();
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
            enrichBooks(List.of(book));
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
        enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findRecentlyAdded(int limit) {
        String sql = "SELECT " + BookQueryBuilder.BOOK_LIST_PROJECTION + " FROM books b "
                + "WHERE b.deleted = 0 ORDER BY b.created_at DESC LIMIT ?";
        List<Book> books = getJdbcTemplate().query(sql, bookListRowMapper, limit);
        enrichBooks(books);
        return books;
    }

    @Override
    public List<Book> findFavoriteAuthors(int limit) {
        String sql = "SELECT " + BookQueryBuilder.BOOK_LIST_PROJECTION + " FROM books b "
                + "WHERE b.deleted = 0 ORDER BY b.rate DESC LIMIT ?";
        List<Book> books = getJdbcTemplate().query(sql, bookListRowMapper, limit);
        enrichBooks(books);
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
            enrichBooks(currentPage);
            currentIndex = 0;
            if (currentPage.isEmpty()) {
                endReached = true;
                return;
            }
            lastId = currentPage.get(currentPage.size() - 1).getId().asString();
            endReached = currentPage.size() < pageSize;
        }
    }

}