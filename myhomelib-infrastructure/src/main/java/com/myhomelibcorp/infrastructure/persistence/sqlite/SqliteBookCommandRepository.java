package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.CollectionTransactionExecutor;
import com.myhomelibcorp.infrastructure.persistence.sqlite.batch.BookBatchWriter;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookDenormalizedValues;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.KeywordIndexSupport;
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

        KeywordIndexSupport.replaceForBook(getJdbcTemplate(), book.getId().asString(), book.getKeywords());

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
        }
        bookCache.evictAll(books.stream().map(Book::getId).toList());
        log.debug("Batch збережено {} книг", books.size());
    }

    @Override
    public void deleteById(BookId id) {
        getJdbcTemplate().update("DELETE FROM keyword_books WHERE book_id = ?", id.asString());
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
        String sql = "UPDATE books SET rate = ? WHERE id = ?";
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
        String sql = "UPDATE books SET progress = ? WHERE id = ?";
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
        String root = collectionRoot == null ? "" : collectionRoot;
        String dir = folder == null ? "" : folder;
        String physicalName = fileName == null ? "" : fileName;
        String entry = archiveEntry == null ? "" : archiveEntry;
        // books.format is the physical storage facet used by the legacy/filter projection,
        // while an artifact describes the logical document inside an archive. Keep both contracts
        // explicit: 729586.fb2.zip is ZIP in books.*, but Dornichev....fb2 is FB2 in artifacts.
        String bookFormat = BookDenormalizedValues.format(physicalName);
        String artifactFormat = BookDenormalizedValues.format(entry.isBlank() ? physicalName : entry);

        busyRetry.run("book storage update", () ->
                CollectionTransactionExecutor.run(collectionManager, () -> {
            getJdbcTemplate().update("""
                    UPDATE books
                    SET collection_root = ?, folder = ?, file_name = ?, archive_entry = ?, local = ?,
                        missing_since = CASE WHEN ? = 1 THEN NULL ELSE COALESCE(missing_since, CURRENT_TIMESTAMP) END,
                        format = ?
                    WHERE id = ?
                    """, root, dir, physicalName, entry, local ? 1 : 0, local ? 1 : 0, bookFormat, bookId.asString());
            synchronizeSourceArtifactStorage(bookId, root, dir, physicalName, entry, local, artifactFormat);
        }));
        bookCache.evict(bookId);
        log.info("DB_BOOK_STORAGE_UPDATE bookId={} local={} physicalFormat={} artifactFormat={} root={} folder={} file={} entry={}",
                bookId.asString(), local, bookFormat, artifactFormat, root, dir, physicalName, entry);
    }

    /**
     * Keeps the durable source artifact in sync with the legacy books.* projection.
     * Download/remove operations historically changed only books.*, leaving an imported
     * artifact REMOTE_ONLY. Re-hydration then preferred that stale artifact and effectively
     * lost the local file for cover/details/conversion. Generated conversion artifacts are
     * deliberately excluded from this synchronization.
     */
    private void synchronizeSourceArtifactStorage(BookId bookId, String root, String folder,
                                                  String physicalName, String archiveEntry,
                                                  boolean local, String format) {
        String id = bookId.asString();
        String logicalName = archiveEntry == null || archiveEntry.isBlank() ? physicalName : archiveEntry;
        String candidate = getJdbcTemplate().query("""
                SELECT ba.artifact_id
                  FROM book_artifacts ba
                  LEFT JOIN book_artifact_preferences p
                    ON p.book_id = ba.book_id AND p.preferred_artifact_id = ba.artifact_id
                 WHERE ba.book_id = ?
                   AND COALESCE(ba.source_id, '') NOT LIKE 'conversion:%'
                 ORDER BY
                    CASE WHEN p.preferred_artifact_id IS NOT NULL THEN 0 ELSE 1 END,
                    CASE WHEN COALESCE(ba.archive_entry, '') = ? AND ? <> '' THEN 0 ELSE 1 END,
                    CASE WHEN COALESCE(ba.artifact_name, '') = ? THEN 0 ELSE 1 END,
                    CASE WHEN COALESCE(NULLIF(ba.archive_name, ''), ba.file_name, '') = ? THEN 0 ELSE 1 END,
                    CASE WHEN ba.local = 1 AND ba.state = 'AVAILABLE' THEN 0 ELSE 1 END,
                    ba.created_at,
                    ba.artifact_id
                 LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null,
                id, archiveEntry, archiveEntry, logicalName, physicalName);

        if (candidate == null || candidate.isBlank()) {
            candidate = "legacy:" + id;
            getJdbcTemplate().update("""
                    INSERT INTO book_artifacts(
                        artifact_id, book_id, source_id, artifact_name, media_type, file_format,
                        file_name, archive_name, archive_entry, size_bytes, sha256, content_fingerprint,
                        remote, local, collection_root, folder, state, created_at, updated_at
                    )
                    SELECT ?, b.id,
                           (SELECT cbs.source_id FROM catalog_book_state cbs WHERE cbs.book_id=b.id LIMIT 1),
                           ?, NULL, ?, ?, ?, ?,
                           CASE WHEN b.file_size IS NULL OR b.file_size < 0 THEN 0 ELSE b.file_size END,
                           NULL, NULL,
                           CASE WHEN EXISTS (SELECT 1 FROM catalog_book_state cbs WHERE cbs.book_id=b.id) THEN 1 ELSE 0 END,
                           ?, ?, ?,
                           CASE WHEN ? = 1 THEN 'AVAILABLE'
                                WHEN EXISTS (SELECT 1 FROM catalog_book_state cbs WHERE cbs.book_id=b.id) THEN 'REMOTE_ONLY'
                                ELSE 'MISSING' END,
                           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                      FROM books b WHERE b.id = ?
                    ON CONFLICT(artifact_id) DO UPDATE SET
                        source_id=COALESCE(excluded.source_id, book_artifacts.source_id),
                        artifact_name=excluded.artifact_name,
                        file_format=excluded.file_format,
                        file_name=excluded.file_name,
                        archive_name=excluded.archive_name,
                        archive_entry=excluded.archive_entry,
                        remote=CASE WHEN excluded.remote = 1 THEN 1 ELSE book_artifacts.remote END,
                        local=excluded.local,
                        collection_root=excluded.collection_root,
                        folder=excluded.folder,
                        state=excluded.state,
                        updated_at=CURRENT_TIMESTAMP
                    """, candidate, logicalName, format,
                    archiveEntry.isBlank() ? physicalName : logicalName,
                    archiveEntry.isBlank() ? null : physicalName,
                    blankToNull(archiveEntry), local ? 1 : 0, blankToNull(root), blankToNull(folder),
                    local ? 1 : 0, id);
        } else {
            getJdbcTemplate().update("""
                    UPDATE book_artifacts
                       SET source_id = COALESCE(source_id,
                               (SELECT cbs.source_id FROM catalog_book_state cbs WHERE cbs.book_id = ? LIMIT 1)),
                           file_format = COALESCE(NULLIF(?, ''), file_format),
                           file_name = ?,
                           archive_name = ?,
                           archive_entry = ?,
                           remote = CASE
                               WHEN remote = 1 THEN 1
                               WHEN EXISTS (SELECT 1 FROM catalog_book_state cbs WHERE cbs.book_id = ?) THEN 1
                               ELSE 0
                           END,
                           local = ?,
                           collection_root = ?,
                           folder = ?,
                           state = CASE
                               WHEN ? = 1 THEN 'AVAILABLE'
                               WHEN remote = 1 OR EXISTS (SELECT 1 FROM catalog_book_state cbs WHERE cbs.book_id = ?)
                                   THEN 'REMOTE_ONLY'
                               ELSE 'MISSING'
                           END,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE book_id = ? AND artifact_id = ?
                    """, id, format,
                    archiveEntry.isBlank() ? physicalName : logicalName,
                    archiveEntry.isBlank() ? null : physicalName,
                    blankToNull(archiveEntry), id, local ? 1 : 0, blankToNull(root), blankToNull(folder),
                    local ? 1 : 0, id, id, candidate);
        }

        Integer conversionPreferred = getJdbcTemplate().queryForObject("""
                SELECT COUNT(*)
                  FROM book_artifact_preferences p
                  JOIN book_artifacts ba
                    ON ba.book_id = p.book_id AND ba.artifact_id = p.preferred_artifact_id
                 WHERE p.book_id = ? AND COALESCE(ba.source_id, '') LIKE 'conversion:%'
                """, Integer.class, id);
        if (conversionPreferred == null || conversionPreferred == 0) {
            getJdbcTemplate().update("""
                    INSERT INTO book_artifact_preferences(book_id, preferred_artifact_id, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT(book_id) DO UPDATE SET
                        preferred_artifact_id=excluded.preferred_artifact_id,
                        updated_at=CURRENT_TIMESTAMP
                    """, id, candidate);
        }
        log.info("DB_BOOK_ARTIFACT_STORAGE_SYNC bookId={} artifactId={} local={} state={}",
                id, candidate, local, local ? "AVAILABLE" : "REMOTE_OR_MISSING");
    }

    @Override
    public void markStorageMissing(BookId bookId) {
        if (bookId == null) return;
        busyRetry.run("book storage missing", () ->
                CollectionTransactionExecutor.run(collectionManager, () -> {
            // Keep the physical-artifact model consistent with books.*. Folder sync verifies the
            // projected books.* path, therefore the artifact representing that exact projection
            // must become unavailable as part of the same transaction. Leaving it AVAILABLE made
            // a restart re-hydrate a file that had already disappeared from disk.
            ensureCanonicalSourceArtifactExists(bookId);
            String id = bookId.asString();
            String projectedArtifact = getJdbcTemplate().query("""
                    SELECT ba.artifact_id
                      FROM book_artifacts ba
                      JOIN books b ON b.id = ba.book_id
                      LEFT JOIN book_artifact_preferences p
                        ON p.book_id = ba.book_id AND p.preferred_artifact_id = ba.artifact_id
                     WHERE ba.book_id = ?
                       AND COALESCE(NULLIF(ba.archive_name, ''), ba.file_name, '') = COALESCE(b.file_name, '')
                       AND COALESCE(ba.folder, '') = COALESCE(b.folder, '')
                       AND COALESCE(ba.archive_entry, '') = COALESCE(b.archive_entry, '')
                       AND COALESCE(ba.collection_root, '') = COALESCE(b.collection_root, '')
                     ORDER BY CASE WHEN p.preferred_artifact_id IS NOT NULL THEN 0 ELSE 1 END, ba.artifact_id
                     LIMIT 1
                    """, rs -> rs.next() ? rs.getString(1) : null, id);

            getJdbcTemplate().update("""
                    UPDATE books
                       SET local = 0,
                           missing_since = COALESCE(missing_since, CURRENT_TIMESTAMP)
                     WHERE id = ?
                    """, id);
            if (projectedArtifact != null && !projectedArtifact.isBlank()) {
                getJdbcTemplate().update("""
                        UPDATE book_artifacts
                           SET local = 0,
                               state = CASE WHEN remote = 1 THEN 'REMOTE_ONLY' ELSE 'MISSING' END,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE book_id = ? AND artifact_id = ?
                        """, id, projectedArtifact);
                log.info("DB_BOOK_ARTIFACT_MISSING bookId={} artifactId={}", id, projectedArtifact);
            }
        }));
        bookCache.evict(bookId);
    }

    @Override
    public void selectPreferredArtifact(BookId bookId, String artifactId) {
        if (bookId == null) throw new IllegalArgumentException("Book id is required");
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("Artifact id is required");
        }
        busyRetry.run("preferred artifact update", () ->
                CollectionTransactionExecutor.run(collectionManager, () -> {
            int selected = getJdbcTemplate().update("""
                    INSERT INTO book_artifact_preferences(book_id, preferred_artifact_id, updated_at)
                    SELECT ?, ba.artifact_id, CURRENT_TIMESTAMP
                      FROM book_artifacts ba
                     WHERE ba.book_id = ? AND ba.artifact_id = ?
                    ON CONFLICT(book_id) DO UPDATE SET
                        preferred_artifact_id = excluded.preferred_artifact_id,
                        updated_at = CURRENT_TIMESTAMP
                    """, bookId.asString(), bookId.asString(), artifactId);
            if (selected == 0) {
                throw new IllegalArgumentException("Artifact does not belong to book: " + artifactId);
            }

            getJdbcTemplate().update("""
                    UPDATE books
                       SET file_name = COALESCE((
                               SELECT COALESCE(NULLIF(ba.archive_name, ''), ba.file_name, '')
                                 FROM book_artifacts ba
                                WHERE ba.book_id = books.id AND ba.artifact_id = ?
                           ), file_name),
                           folder = COALESCE((
                               SELECT ba.folder FROM book_artifacts ba
                                WHERE ba.book_id = books.id AND ba.artifact_id = ?
                           ), ''),
                           archive_entry = COALESCE((
                               SELECT ba.archive_entry FROM book_artifacts ba
                                WHERE ba.book_id = books.id AND ba.artifact_id = ?
                           ), ''),
                           file_size = COALESCE((
                               SELECT ba.size_bytes FROM book_artifacts ba
                                WHERE ba.book_id = books.id AND ba.artifact_id = ?
                           ), 0),
                           collection_root = COALESCE((
                               SELECT ba.collection_root FROM book_artifacts ba
                                WHERE ba.book_id = books.id AND ba.artifact_id = ?
                           ), ''),
                           local = COALESCE((
                               SELECT ba.local FROM book_artifacts ba
                                WHERE ba.book_id = books.id AND ba.artifact_id = ?
                           ), 0),
                           missing_since = CASE
                               WHEN COALESCE((SELECT ba.local FROM book_artifacts ba
                                              WHERE ba.book_id = books.id AND ba.artifact_id = ?), 0) = 1
                               THEN NULL
                               ELSE COALESCE(missing_since, CURRENT_TIMESTAMP)
                           END
                     WHERE id = ?
                    """, artifactId, artifactId, artifactId, artifactId, artifactId, artifactId, artifactId,
                    bookId.asString());
            canonicalizeBookProjectionFormat(bookId);
        }));
        bookCache.evict(bookId);
        log.debug("Preferred artifact для книги {}: {}", bookId.asString(), artifactId);
    }

    @Override
    public void upsertArtifact(BookId bookId, BookArtifact artifact, boolean makePreferred) {
        if (bookId == null) throw new IllegalArgumentException("Book id is required");
        if (artifact == null) throw new IllegalArgumentException("Artifact is required");
        busyRetry.run("book artifact upsert", () ->
                CollectionTransactionExecutor.run(collectionManager, () -> {
            // Secondary artifacts must never become primary merely because an older/imported
            // catalogue has no explicit preference row yet. Capture the current canonical
            // books.* projection before inserting the new representation, so re-hydration and
            // later edits keep the original source until the user explicitly promotes another artifact.
            if (!makePreferred) ensurePreferredArtifactForCurrentProjection(bookId);
            var file = artifact.getFile();
            int changed = getJdbcTemplate().update("""
                    INSERT INTO book_artifacts(
                        artifact_id, book_id, source_id, artifact_name, media_type, file_format,
                        file_name, archive_name, archive_entry, size_bytes, sha256, content_fingerprint,
                        remote, local, collection_root, folder, state, created_at, updated_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    ON CONFLICT(artifact_id) DO UPDATE SET
                        source_id=excluded.source_id,
                        artifact_name=excluded.artifact_name,
                        media_type=excluded.media_type,
                        file_format=excluded.file_format,
                        file_name=excluded.file_name,
                        archive_name=excluded.archive_name,
                        archive_entry=excluded.archive_entry,
                        size_bytes=excluded.size_bytes,
                        sha256=excluded.sha256,
                        content_fingerprint=excluded.content_fingerprint,
                        remote=excluded.remote,
                        local=excluded.local,
                        collection_root=excluded.collection_root,
                        folder=excluded.folder,
                        state=excluded.state,
                        updated_at=CURRENT_TIMESTAMP
                    WHERE book_artifacts.book_id=excluded.book_id
                    """,
                    artifact.getId(), bookId.asString(), blankToNull(artifact.getSourceId()), artifact.getName(),
                    blankToNull(artifact.getMediaType()), blankToNull(artifact.getFormat()),
                    blankToNull(file.getArchiveEntry() == null || file.getArchiveEntry().isBlank()
                            ? file.getFileName() : file.getArchiveEntry()),
                    blankToNull(file.getArchiveEntry() == null || file.getArchiveEntry().isBlank()
                            ? null : file.getFileName()),
                    blankToNull(file.getArchiveEntry()),
                    Math.max(0L, file.getFileSize()), blankToNull(artifact.getSha256()),
                    blankToNull(artifact.getContentFingerprint()), artifact.isRemote() ? 1 : 0, artifact.isLocal() ? 1 : 0,
                    blankToNull(file.getCollectionRoot()), blankToNull(file.getFolder()), artifact.getState().name());
            if (changed == 0) {
                throw new IllegalArgumentException("Artifact id already belongs to another book: " + artifact.getId());
            }

            getJdbcTemplate().update("DELETE FROM book_artifact_metadata WHERE artifact_id=?", artifact.getId());
            if (artifact.getMetadata() != null && !artifact.getMetadata().isEmpty()) {
                getJdbcTemplate().batchUpdate(
                        "INSERT INTO book_artifact_metadata(artifact_id,metadata_key,metadata_value) VALUES (?,?,?)",
                        artifact.getMetadata().entrySet(),
                        100,
                        (ps, entry) -> {
                            ps.setString(1, artifact.getId());
                            ps.setString(2, entry.getKey());
                            ps.setString(3, entry.getValue());
                        });
            }

            if (makePreferred) {
                getJdbcTemplate().update("""
                        INSERT INTO book_artifact_preferences(book_id, preferred_artifact_id, updated_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT(book_id) DO UPDATE SET
                            preferred_artifact_id=excluded.preferred_artifact_id,
                            updated_at=CURRENT_TIMESTAMP
                        """, bookId.asString(), artifact.getId());
                updatePreferredProjection(bookId, artifact.getId());
            }
        }));
        bookCache.evict(bookId);
        log.debug("Artifact upsert for book {}: {} preferred={}", bookId.asString(), artifact.getId(), makePreferred);
    }

    private void ensurePreferredArtifactForCurrentProjection(BookId bookId) {
        String id = bookId.asString();
        ensureCanonicalSourceArtifactExists(bookId);
        // First preserve an exact books.* storage match. This is the durable legacy/canonical
        // projection and is the strongest evidence of what was primary before multi-artifact data.
        getJdbcTemplate().update("""
                INSERT OR IGNORE INTO book_artifact_preferences(book_id, preferred_artifact_id, updated_at)
                SELECT b.id,
                       (
                           SELECT ba.artifact_id
                             FROM book_artifacts ba
                            WHERE ba.book_id = b.id
                              AND COALESCE(NULLIF(ba.archive_name, ''), ba.file_name, '') = COALESCE(b.file_name, '')
                              AND COALESCE(ba.folder, '') = COALESCE(b.folder, '')
                              AND COALESCE(ba.archive_entry, '') = COALESCE(b.archive_entry, '')
                              AND COALESCE(ba.collection_root, '') = COALESCE(b.collection_root, '')
                            ORDER BY
                                CASE WHEN COALESCE(ba.source_id, '') LIKE 'conversion:%' THEN 1 ELSE 0 END,
                                ba.artifact_id
                            LIMIT 1
                       ),
                       CURRENT_TIMESTAMP
                  FROM books b
                 WHERE b.id = ?
                   AND EXISTS (
                       SELECT 1 FROM book_artifacts ba
                        WHERE ba.book_id = b.id
                          AND COALESCE(NULLIF(ba.archive_name, ''), ba.file_name, '') = COALESCE(b.file_name, '')
                          AND COALESCE(ba.folder, '') = COALESCE(b.folder, '')
                          AND COALESCE(ba.archive_entry, '') = COALESCE(b.archive_entry, '')
                          AND COALESCE(ba.collection_root, '') = COALESCE(b.collection_root, '')
                   )
                """, id);

        // If a historical/imported row has no exact projection match, prefer a non-conversion
        // local/available representation. The generated artifact being inserted by the caller is
        // intentionally not visible yet, so it cannot win this fallback.
        getJdbcTemplate().update("""
                INSERT OR IGNORE INTO book_artifact_preferences(book_id, preferred_artifact_id, updated_at)
                SELECT b.id,
                       (
                           SELECT ba.artifact_id
                             FROM book_artifacts ba
                            WHERE ba.book_id = b.id
                            ORDER BY
                                CASE WHEN COALESCE(ba.source_id, '') LIKE 'conversion:%' THEN 1 ELSE 0 END,
                                CASE WHEN ba.local = 1 AND ba.state = 'AVAILABLE' THEN 0 ELSE 1 END,
                                CASE WHEN ba.local = 1 THEN 0 ELSE 1 END,
                                ba.created_at,
                                ba.artifact_id
                            LIMIT 1
                       ),
                       CURRENT_TIMESTAMP
                  FROM books b
                 WHERE b.id = ?
                   AND EXISTS (SELECT 1 FROM book_artifacts ba WHERE ba.book_id = b.id)
                """, id);
    }

    private void ensureCanonicalSourceArtifactExists(BookId bookId) {
        String id = bookId.asString();
        Integer sources = getJdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM book_artifacts
                 WHERE book_id = ? AND COALESCE(source_id, '') NOT LIKE 'conversion:%'
                """, Integer.class, id);
        if (sources != null && sources > 0) return;

        String legacyId = "legacy:" + id;
        getJdbcTemplate().update("""
                INSERT INTO book_artifacts(
                    artifact_id, book_id, source_id, artifact_name, media_type, file_format,
                    file_name, archive_name, archive_entry, size_bytes, sha256, content_fingerprint,
                    remote, local, collection_root, folder, state, created_at, updated_at
                )
                SELECT ?, b.id,
                       (SELECT cbs.source_id FROM catalog_book_state cbs WHERE cbs.book_id=b.id LIMIT 1),
                       CASE WHEN COALESCE(b.archive_entry, '') <> '' THEN b.archive_entry
                            WHEN COALESCE(b.file_name, '') <> '' THEN b.file_name
                            ELSE 'book-' || b.id END,
                       NULL, NULLIF(b.format, ''),
                       CASE WHEN COALESCE(b.archive_entry, '') <> '' THEN b.archive_entry ELSE b.file_name END,
                       CASE WHEN COALESCE(b.archive_entry, '') <> '' THEN b.file_name ELSE NULL END,
                       NULLIF(b.archive_entry, ''),
                       CASE WHEN b.file_size IS NULL OR b.file_size < 0 THEN 0 ELSE b.file_size END,
                       NULL, NULL,
                       CASE WHEN EXISTS (SELECT 1 FROM catalog_book_state cbs WHERE cbs.book_id=b.id) THEN 1 ELSE 0 END,
                       CASE WHEN b.local = 1 THEN 1 ELSE 0 END,
                       NULLIF(b.collection_root, ''), NULLIF(b.folder, ''),
                       CASE WHEN b.local = 1 THEN 'AVAILABLE'
                            WHEN EXISTS (SELECT 1 FROM catalog_book_state cbs WHERE cbs.book_id=b.id) THEN 'REMOTE_ONLY'
                            ELSE 'MISSING' END,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                  FROM books b WHERE b.id = ?
                ON CONFLICT(artifact_id) DO NOTHING
                """, legacyId, id);
        List<String> logicalNames = getJdbcTemplate().query(
                "SELECT CASE WHEN COALESCE(archive_entry, '') <> '' THEN archive_entry ELSE file_name END FROM books WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), id);
        if (!logicalNames.isEmpty()) {
            getJdbcTemplate().update(
                    "UPDATE book_artifacts SET file_format = ? WHERE artifact_id = ? AND book_id = ?",
                    BookDenormalizedValues.format(logicalNames.getFirst()), legacyId, id);
        }
        log.info("DB_BOOK_ARTIFACT_SOURCE_BACKFILL bookId={} artifactId={}", id, legacyId);
    }

    private void updatePreferredProjection(BookId bookId, String artifactId) {
        getJdbcTemplate().update("""
                UPDATE books
                   SET file_name = COALESCE((
                           SELECT COALESCE(NULLIF(ba.archive_name, ''), ba.file_name, '')
                             FROM book_artifacts ba
                            WHERE ba.book_id = books.id AND ba.artifact_id = ?
                       ), file_name),
                       folder = COALESCE((SELECT ba.folder FROM book_artifacts ba
                                          WHERE ba.book_id = books.id AND ba.artifact_id = ?), ''),
                       archive_entry = COALESCE((SELECT ba.archive_entry FROM book_artifacts ba
                                                 WHERE ba.book_id = books.id AND ba.artifact_id = ?), ''),
                       file_size = COALESCE((SELECT ba.size_bytes FROM book_artifacts ba
                                             WHERE ba.book_id = books.id AND ba.artifact_id = ?), 0),
                       collection_root = COALESCE((SELECT ba.collection_root FROM book_artifacts ba
                                                   WHERE ba.book_id = books.id AND ba.artifact_id = ?), ''),
                       local = COALESCE((SELECT ba.local FROM book_artifacts ba
                                         WHERE ba.book_id = books.id AND ba.artifact_id = ?), 0),
                       missing_since = CASE
                           WHEN COALESCE((SELECT ba.local FROM book_artifacts ba
                                          WHERE ba.book_id = books.id AND ba.artifact_id = ?), 0) = 1
                           THEN NULL ELSE COALESCE(missing_since, CURRENT_TIMESTAMP) END
                 WHERE id = ?
                """, artifactId, artifactId, artifactId, artifactId, artifactId, artifactId, artifactId,
                bookId.asString());
        canonicalizeBookProjectionFormat(bookId);
    }

    /**
     * books.format is a canonical facet of the projected physical file, not a copy of
     * book_artifacts.file_format. This matters for archives (ZIP versus logical FB2) and
     * for registry aliases such as SEVEN_Z. Re-derive it after every artifact projection.
     */
    private void canonicalizeBookProjectionFormat(BookId bookId) {
        List<String> fileNames = getJdbcTemplate().query(
                "SELECT file_name FROM books WHERE id = ?",
                (rs, rowNum) -> rs.getString(1),
                bookId.asString());
        if (fileNames.isEmpty()) return;
        getJdbcTemplate().update("UPDATE books SET format = ? WHERE id = ?",
                BookDenormalizedValues.format(fileNames.getFirst()), bookId.asString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
            String sql = "UPDATE books SET rate = ? WHERE id IN ("
                    + SqliteInClauseSupport.placeholders(part.size()) + ")";
            Object[] params = new Object[part.size() + 1];
            params[0] = rate;
            for (int i = 0; i < part.size(); i++) params[i + 1] = part.get(i).asString();
            updated[0] += getJdbcTemplate().update(sql, params);
        });
        log.info("Batch оновлено рейтинг для {} книг", updated[0]);
        bookCache.evictAll(bookIds);
    }

    @Override
    public void updateProgressBatch(List<BookId> bookIds, int progress) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        int[] updated = {0};
        SqliteInClauseSupport.forEachChunk(bookIds, part -> {
            String sql = "UPDATE books SET progress = ? WHERE id IN ("
                    + SqliteInClauseSupport.placeholders(part.size()) + ")";
            Object[] params = new Object[part.size() + 1];
            params[0] = progress;
            for (int i = 0; i < part.size(); i++) params[i + 1] = part.get(i).asString();
            updated[0] += getJdbcTemplate().update(sql, params);
        });
        log.info("Batch оновлено прогрес для {} книг", updated[0]);
        bookCache.evictAll(bookIds);
    }
}