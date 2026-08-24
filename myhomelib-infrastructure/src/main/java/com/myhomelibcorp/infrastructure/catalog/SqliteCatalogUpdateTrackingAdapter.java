package com.myhomelibcorp.infrastructure.catalog;

import com.myhomelibcorp.application.catalog.CatalogBookSnapshot;
import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.catalog.CatalogSyncSession;
import com.myhomelibcorp.application.catalog.CatalogUpdateRecord;
import com.myhomelibcorp.application.catalog.CatalogUpdateType;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** SQLite implementation of the Stage 6 catalog source/book revision model. */
@Component
@RequiredArgsConstructor
public class SqliteCatalogUpdateTrackingAdapter implements CatalogUpdateTrackingPort {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String UPDATED = CatalogUpdateType.UPDATED_DOWNLOADED_BOOK.name();
    private static final String NEW_FOLLOWED = CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR.name();

    private final CollectionManager collectionManager;

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public CatalogSyncSession beginSync(String sourceKey, String sourceLocation, String sourceFingerprint) {
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey is required");
        if (sourceFingerprint == null || sourceFingerprint.isBlank()) throw new IllegalArgumentException("sourceFingerprint is required");

        String normalizedKey = sourceKey.trim();
        String sourceId = CatalogSourceIdentity.stableId(normalizedKey);
        String safeLocation = sanitizeLocation(sourceLocation);
        List<SourceRow> existing = jdbc().query(
                "SELECT source_id, source_revision, source_fingerprint FROM catalog_sources WHERE source_key = ?",
                (rs, rowNum) -> new SourceRow(rs.getString(1), rs.getLong(2), rs.getString(3)),
                normalizedKey);
        String now = now();

        if (existing.isEmpty()) {
            jdbc().update("""
                    INSERT INTO catalog_sources(
                        source_id, source_key, source_location, source_revision,
                        source_fingerprint, first_seen_at, last_synced_at
                    ) VALUES (?, ?, ?, 1, ?, ?, ?)
                    """, sourceId, normalizedKey, safeLocation, sourceFingerprint, now, now);
            return new CatalogSyncSession(sourceId, normalizedKey, 1L, sourceFingerprint, true, true);
        }

        SourceRow row = existing.getFirst();
        long revision = row.revision();
        boolean changed = !sourceFingerprint.equals(row.fingerprint());
        if (changed) revision++;
        jdbc().update("""
                UPDATE catalog_sources
                   SET source_location = ?, source_revision = ?, source_fingerprint = ?, last_synced_at = ?
                 WHERE source_key = ?
                """, safeLocation, revision, sourceFingerprint, now, normalizedKey);
        return new CatalogSyncSession(row.sourceId(), normalizedKey, revision, sourceFingerprint, false, changed);
    }

    @Override
    public void markTrackedBooksMissing(CatalogSyncSession session) {
        if (session == null) return;
        // Keep local=1 and user-owned storage untouched; absence from the new catalog only changes catalog visibility.
        jdbc().update("""
                UPDATE books
                   SET deleted = 1
                 WHERE id IN (SELECT book_id FROM catalog_book_state WHERE source_id = ?)
                """, session.sourceId());
    }

    @Override
    public void recordImportedBooks(CatalogSyncSession session, List<CatalogBookSnapshot> books) {
        if (session == null || books == null || books.isEmpty()) return;
        String detectedAt = now();

        // If the catalog reverted to exactly the downloaded baseline, an outstanding UPDATED event is no longer valid.
        jdbc().batchUpdate("""
                DELETE FROM catalog_update_events
                 WHERE book_id = ? AND update_type = 'UPDATED_DOWNLOADED_BOOK'
                   AND EXISTS (
                       SELECT 1 FROM catalog_book_state c
                        WHERE c.book_id = ?
                          AND c.downloaded_fingerprint IS NOT NULL
                          AND c.downloaded_fingerprint = ?
                   )
                """, books, 1000, (ps, book) -> {
            ps.setString(1, book.bookId());
            ps.setString(2, book.bookId());
            ps.setString(3, book.catalogFingerprint());
        });

        // A downloaded book becomes pending only when the incoming catalog state differs both
        // from the prior catalog state and from the bytes/revision captured at download time.
        jdbc().batchUpdate("""
                INSERT INTO catalog_update_events(
                    book_id, update_type, source_id, detected_revision,
                    catalog_fingerprint, detected_at, acknowledged_at
                )
                SELECT ?, 'UPDATED_DOWNLOADED_BOOK', ?, ?, ?, ?, NULL
                 WHERE EXISTS (
                     SELECT 1
                       FROM catalog_book_state c
                       JOIN books b ON b.id = c.book_id
                      WHERE c.book_id = ?
                        AND b.local = 1
                        AND c.downloaded_fingerprint IS NOT NULL
                        AND c.catalog_fingerprint <> ?
                        AND c.downloaded_fingerprint <> ?
                 )
                ON CONFLICT(book_id, update_type) DO UPDATE SET
                    source_id = excluded.source_id,
                    detected_revision = excluded.detected_revision,
                    catalog_fingerprint = excluded.catalog_fingerprint,
                    detected_at = excluded.detected_at,
                    acknowledged_at = NULL
                WHERE catalog_update_events.catalog_fingerprint <> excluded.catalog_fingerprint
                """, books, 1000, (ps, book) -> {
            int i = 1;
            ps.setString(i++, book.bookId());
            ps.setString(i++, session.sourceId());
            ps.setLong(i++, session.sourceRevision());
            ps.setString(i++, book.catalogFingerprint());
            ps.setString(i++, detectedAt);
            ps.setString(i++, book.bookId());
            ps.setString(i++, book.catalogFingerprint());
            ps.setString(i, book.catalogFingerprint());
        });

        // Initial adoption of an existing catalog is a baseline, not a flood of "new" books.
        if (!session.initialBaseline()) {
            jdbc().batchUpdate("""
                    INSERT INTO catalog_update_events(
                        book_id, update_type, source_id, detected_revision,
                        catalog_fingerprint, detected_at, acknowledged_at
                    )
                    SELECT ?, 'NEW_BY_FOLLOWED_AUTHOR', ?, ?, ?, ?, NULL
                     WHERE NOT EXISTS (SELECT 1 FROM catalog_book_state c WHERE c.book_id = ?)
                       AND EXISTS (
                           SELECT 1
                             FROM book_authors ba
                             JOIN followed_authors fa ON fa.author_id = ba.author_id
                            WHERE ba.book_id = ?
                       )
                    ON CONFLICT(book_id, update_type) DO UPDATE SET
                        source_id = excluded.source_id,
                        detected_revision = excluded.detected_revision,
                        catalog_fingerprint = excluded.catalog_fingerprint,
                        detected_at = excluded.detected_at,
                        acknowledged_at = NULL
                    WHERE catalog_update_events.catalog_fingerprint <> excluded.catalog_fingerprint
                    """, books, 1000, (ps, book) -> {
                int i = 1;
                ps.setString(i++, book.bookId());
                ps.setString(i++, session.sourceId());
                ps.setLong(i++, session.sourceRevision());
                ps.setString(i++, book.catalogFingerprint());
                ps.setString(i++, detectedAt);
                ps.setString(i++, book.bookId());
                ps.setString(i, book.bookId());
            });
        }

        // State UPSERT deliberately never overwrites downloaded_* on conflict. New legacy/local rows
        // get a baseline automatically so the first Stage 6 sync cannot create false update alerts.
        jdbc().batchUpdate("""
                INSERT INTO catalog_book_state(
                    book_id, source_id, source_book_key, catalog_revision, catalog_fingerprint,
                    catalog_file_name, catalog_folder, catalog_archive_entry, catalog_file_size,
                    downloaded_revision, downloaded_fingerprint, downloaded_baseline_at,
                    first_seen_revision, last_seen_revision
                )
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?,
                       CASE WHEN b.local = 1 THEN ? ELSE NULL END,
                       CASE WHEN b.local = 1 THEN ? ELSE NULL END,
                       CASE WHEN b.local = 1 THEN ? ELSE NULL END,
                       ?, ?
                  FROM books b WHERE b.id = ?
                ON CONFLICT(book_id) DO UPDATE SET
                    source_id = excluded.source_id,
                    source_book_key = excluded.source_book_key,
                    catalog_revision = excluded.catalog_revision,
                    catalog_fingerprint = excluded.catalog_fingerprint,
                    catalog_file_name = excluded.catalog_file_name,
                    catalog_folder = excluded.catalog_folder,
                    catalog_archive_entry = excluded.catalog_archive_entry,
                    catalog_file_size = excluded.catalog_file_size,
                    last_seen_revision = excluded.last_seen_revision
                """, books, 1000, (ps, book) -> {
            int i = 1;
            ps.setString(i++, book.bookId());
            ps.setString(i++, session.sourceId());
            ps.setString(i++, book.sourceBookKey());
            ps.setLong(i++, session.sourceRevision());
            ps.setString(i++, book.catalogFingerprint());
            ps.setString(i++, book.fileName());
            ps.setString(i++, book.folder());
            ps.setString(i++, book.archiveEntry());
            ps.setLong(i++, book.fileSize());
            ps.setLong(i++, session.sourceRevision());
            ps.setString(i++, book.catalogFingerprint());
            ps.setString(i++, detectedAt);
            ps.setLong(i++, session.sourceRevision());
            ps.setLong(i++, session.sourceRevision());
            ps.setString(i, book.bookId());
        });
    }

    @Override
    public void markDownloadedBaseline(BookId bookId) {
        if (bookId == null) return;
        String now = now();
        jdbc().update("""
                UPDATE catalog_book_state
                   SET downloaded_revision = catalog_revision,
                       downloaded_fingerprint = catalog_fingerprint,
                       downloaded_baseline_at = ?
                 WHERE book_id = ?
                """, now, bookId.asString());
        // A successful download resolves both kinds of pending catalog notification for this book:
        // it establishes the downloaded baseline and consumes a "new by followed author" event too.
        jdbc().update("""
                UPDATE catalog_update_events
                   SET acknowledged_at = ?
                 WHERE book_id = ? AND acknowledged_at IS NULL
                """, now, bookId.asString());
    }

    @Override
    public void clearDownloadedBaseline(BookId bookId) {
        if (bookId == null) return;
        String now = now();
        jdbc().update("""
                UPDATE catalog_book_state
                   SET downloaded_revision = NULL,
                       downloaded_fingerprint = NULL,
                       downloaded_baseline_at = NULL
                 WHERE book_id = ?
                """, bookId.asString());
        // A successful download resolves both kinds of pending catalog notification for this book:
        // it establishes the downloaded baseline and consumes a "new by followed author" event too.
        jdbc().update("""
                UPDATE catalog_update_events
                   SET acknowledged_at = ?
                 WHERE book_id = ? AND acknowledged_at IS NULL
                """, now, bookId.asString());
    }

    @Override
    public void setAuthorFollowed(AuthorId authorId, boolean followed) {
        if (authorId == null) return;
        if (followed) {
            jdbc().update("INSERT OR IGNORE INTO followed_authors(author_id, followed_at) VALUES (?, ?)",
                    authorId.asString(), now());
        } else {
            jdbc().update("DELETE FROM followed_authors WHERE author_id = ?", authorId.asString());
        }
    }

    @Override
    public boolean isAuthorFollowed(AuthorId authorId) {
        if (authorId == null) return false;
        Integer count = jdbc().queryForObject(
                "SELECT COUNT(*) FROM followed_authors WHERE author_id = ?", Integer.class, authorId.asString());
        return count != null && count > 0;
    }

    @Override
    public List<CatalogUpdateRecord> findPendingUpdates(int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 10_000));
        int safeOffset = Math.max(0, offset);
        return jdbc().query("""
                SELECT book_id, update_type, source_id, detected_revision, catalog_fingerprint, detected_at
                  FROM catalog_update_events
                 WHERE acknowledged_at IS NULL
                 ORDER BY detected_at DESC, book_id ASC, update_type ASC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new CatalogUpdateRecord(
                        rs.getString("book_id"),
                        CatalogUpdateType.valueOf(rs.getString("update_type")),
                        rs.getString("source_id"),
                        rs.getLong("detected_revision"),
                        rs.getString("catalog_fingerprint"),
                        rs.getString("detected_at")), safeLimit, safeOffset);
    }

    @Override
    public long countPendingUpdates() {
        Long count = jdbc().queryForObject(
                "SELECT COUNT(*) FROM catalog_update_events WHERE acknowledged_at IS NULL", Long.class);
        return count == null ? 0L : count;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    /** Do not persist credentials, signed query tokens or URL fragments in diagnostics. */
    private static String sanitizeLocation(String sourceLocation) {
        if (sourceLocation == null || sourceLocation.isBlank()) return null;
        String value = sourceLocation.trim();
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null) return value.replace('\\', '/');
            String host = uri.getHost();
            int port = uri.getPort();
            if (("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)) port = -1;
            return new URI(uri.getScheme().toLowerCase(), null,
                    host == null ? null : host.toLowerCase(), port,
                    uri.getPath(), null, null).normalize().toString();
        } catch (Exception ignored) {
            return value.replace('\\', '/');
        }
    }

    private record SourceRow(String sourceId, long revision, String fingerprint) { }
}
