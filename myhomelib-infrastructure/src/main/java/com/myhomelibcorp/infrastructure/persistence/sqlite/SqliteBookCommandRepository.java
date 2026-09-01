package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.batch.BookBatchWriter;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookDenormalizedValues;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport;
import com.myhomelibcorp.infrastructure.persistence.sqlite.query.BookQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteBookCommandRepository implements BookCommandRepository {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final CollectionManager collectionManager;
    private final BookAuthorHelper bookAuthorHelper;
    private final BookGenreHelper bookGenreHelper;
    private final BookBatchWriter batchWriter;
    private final BookCache bookCache;
    private final SqliteBusyRetryExecutor busyRetry;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    // ==================== ОСНОВНІ МЕТОДИ ====================

    @Override
    public Book save(Book book) {
        getJdbcTemplate().update(connection -> {
            PreparedStatement ps = connection.prepareStatement(BookQueries.INSERT_OR_UPDATE_BOOK);
            int idx = 1;
            ps.setString(idx++, book.getId().asString());
            ps.setString(idx++, book.getTitle() != null ? book.getTitle() : "");
            ps.setString(idx++, book.getSeries());
            ps.setInt(idx++, book.getSequenceNumber() != null ? book.getSequenceNumber() : 0);
            ps.setString(idx++, book.getFileName() != null ? book.getFileName() : "");
            ps.setString(idx++, book.getFolder());
            ps.setString(idx++, book.getArchiveEntry());
            ps.setString(idx++, book.getLanguage() != null ? book.getLanguage().toString() : null);
            ps.setLong(idx++, book.getFileSize());
            ps.setString(idx++, book.getKeywords() != null ? book.getKeywords() : "");
            ps.setString(idx++, book.getAnnotation() != null ? book.getAnnotation() : "");
            ps.setInt(idx++, book.getRate());
            ps.setInt(idx++, book.getProgress());
            String formattedDate = book.getUpdateDate() != null
                    ? book.getUpdateDate().format(DATE_FORMATTER)
                    : null;
            ps.setString(idx++, formattedDate);
            ps.setString(idx++, book.getIsbn() != null ? book.getIsbn().toString() : null);
            ps.setInt(idx++, book.isDeleted() ? 1 : 0);
            ps.setInt(idx++, book.isLocal() ? 1 : 0);
            ps.setString(idx++, book.getReview() != null ? book.getReview() : "");
            String formattedCreated = book.getCreatedAt() != null
                    ? book.getCreatedAt().format(DATE_FORMATTER)
                    : LocalDateTime.now().format(DATE_FORMATTER);
            ps.setString(idx++, formattedCreated);
            ps.setString(idx++, book.getCollectionRoot() != null ? book.getCollectionRoot() : "");
            if (book.getYear() != null) ps.setInt(idx++, book.getYear()); else ps.setNull(idx++, java.sql.Types.INTEGER);
            ps.setString(idx++, book.getPublisher() != null ? book.getPublisher() : "");
            ps.setString(idx++, book.getLibId() != null ? book.getLibId() : "");
            ps.setInt(idx++, book.getLibraryRate());
            ps.setString(idx++, book.getTranslators() != null ? book.getTranslators() : "");
            ps.setString(idx++, book.getCity() != null ? book.getCity() : "");
            ps.setString(idx++, book.getSourceUrl() != null ? book.getSourceUrl() : "");
            ps.setString(idx++, BookDenormalizedValues.format(book.getFileName()));
            ps.setString(idx++, BookDenormalizedValues.authorSort(book));
            return ps;
        });

        // Always replace relationship links. An empty parsed list means stale links
        // from a previous version of the file must be removed.
        bookAuthorHelper.saveAuthors(book.getId(),
                book.getAuthors() == null ? java.util.List.of() : book.getAuthors());
        bookGenreHelper.saveGenres(book.getId(),
                book.getGenres() == null ? java.util.List.of() : book.getGenres());

        bookCache.evict(book.getId());

        log.debug("Книгу збережено: id={}, title={}", book.getId().asString(), book.getTitle());
        return book;
    }

    @Override
    public void saveBatch(List<Book> books) {
        if (books == null || books.isEmpty()) return;
        batchWriter.batchInsert(books);
        for (Book book : books) {
            bookAuthorHelper.saveAuthors(book.getId(),
                    book.getAuthors() == null ? java.util.List.of() : book.getAuthors());
            bookGenreHelper.saveGenres(book.getId(),
                    book.getGenres() == null ? java.util.List.of() : book.getGenres());
            bookCache.evict(book.getId());
        }
        log.debug("Batch збережено {} книг", books.size());
    }

    @Override
    public void deleteById(BookId id) {
        getJdbcTemplate().update(BookQueries.DELETE_BY_ID, id.asString());
        bookCache.evict(id);
        log.debug("Книгу видалено: id={}", id.asString());
    }

    @Override
    public void updateRate(BookId bookId, int rate) {
        if (bookId == null) {
            log.error("Спроба оновити рейтинг з null BookId");
            return;
        }
        String sql = "UPDATE books SET rate = ?, update_date = CURRENT_TIMESTAMP WHERE id = ?";
        int updated = getJdbcTemplate().update(sql, rate, bookId.asString());
        if (updated > 0) {
            log.debug("Оновлено рейтинг для книги {}: {}", bookId, rate);
            bookCache.evict(bookId);
        } else {
            log.warn("Не вдалося оновити рейтинг для книги {} (книгу не знайдено)", bookId);
        }
    }

    @Override
    public void updateProgress(BookId bookId, int progress) {
        if (bookId == null) {
            log.error("Спроба оновити прогрес з null BookId");
            return;
        }
        if (progress < 0 || progress > 100) {
            log.warn("Неправильне значення прогресу: {} для книги {}", progress, bookId);
            return;
        }

        // Використовуємо CURRENT_TIMESTAMP для узгодженості з іншими методами
        String sql = "UPDATE books SET progress = ?, update_date = CURRENT_TIMESTAMP WHERE id = ?";
        int updated = getJdbcTemplate().update(sql, progress, bookId.asString());

        if (updated > 0) {
            log.info("✅ SQL оновлено прогрес для книги {}: {}%", bookId, progress);
            bookCache.evict(bookId);
        } else {
            log.warn("❌ Не вдалося оновити прогрес для книги {} (книгу не знайдено)", bookId);
        }
    }

    @Override
    public void updateStorage(BookId bookId, String collectionRoot, String folder, String fileName, String archiveEntry, boolean local) {
        if (bookId == null) return;
        busyRetry.run("book storage update", () -> getJdbcTemplate().update("""
                UPDATE books
                SET collection_root = ?, folder = ?, file_name = ?, archive_entry = ?, local = ?,
                    format = ?, update_date = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                collectionRoot == null ? "" : collectionRoot,
                folder == null ? "" : folder,
                fileName == null ? "" : fileName,
                archiveEntry == null ? "" : archiveEntry,
                local ? 1 : 0,
                BookDenormalizedValues.format(fileName),
                bookId.asString()));
        bookCache.evict(bookId);
    }

    @Override
    public int repairTransientRemoteStorageRoots(String permanentRoot) {
        if (permanentRoot == null || permanentRoot.isBlank()) return 0;
        int updated = getJdbcTemplate().update("""
                UPDATE books
                   SET collection_root = ?
                 WHERE local = 0
                   AND collection_root IS NOT NULL
                   AND LOWER(REPLACE(collection_root, CHAR(92), '/'))
                       LIKE '%/.myhomelibcorp/cache/catalog-updates%'
                """, permanentRoot);
        if (updated > 0) {
            bookCache.clear();
            log.info("Виправлено transient catalog root для {} remote книг", updated);
        }
        return updated;
    }

    // ==================== НОВІ БАТЧ-МЕТОДИ ====================

    @Override
    public void updateRateBatch(List<BookId> bookIds, int rate) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        int[] updated = {0};
        SqliteInClauseSupport.forEachChunk(bookIds, part -> {
            String sql = "UPDATE books SET rate = ?, update_date = CURRENT_TIMESTAMP WHERE id IN ("
                    + SqliteInClauseSupport.placeholders(part.size()) + ")";
            Object[] params = new Object[part.size() + 1];
            params[0] = rate;
            for (int i = 0; i < part.size(); i++) params[i + 1] = part.get(i).asString();
            updated[0] += getJdbcTemplate().update(sql, params);
        });
        log.info("Batch оновлено рейтинг для {} книг", updated[0]);
        bookIds.forEach(bookCache::evict);
    }

    @Override
    public void updateProgressBatch(List<BookId> bookIds, int progress) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        int[] updated = {0};
        SqliteInClauseSupport.forEachChunk(bookIds, part -> {
            String sql = "UPDATE books SET progress = ?, update_date = CURRENT_TIMESTAMP WHERE id IN ("
                    + SqliteInClauseSupport.placeholders(part.size()) + ")";
            Object[] params = new Object[part.size() + 1];
            params[0] = progress;
            for (int i = 0; i < part.size(); i++) params[i + 1] = part.get(i).asString();
            updated[0] += getJdbcTemplate().update(sql, params);
        });
        log.info("Batch оновлено прогрес для {} книг", updated[0]);
        bookIds.forEach(bookCache::evict);
    }
}