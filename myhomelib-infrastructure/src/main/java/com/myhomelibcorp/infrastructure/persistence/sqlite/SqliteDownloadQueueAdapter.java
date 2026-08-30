package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.download.DownloadQueueEntry;
import com.myhomelibcorp.application.download.DownloadQueueStatus;
import com.myhomelibcorp.application.port.out.download.DownloadQueuePort;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Metadata-DB implementation of the restart-safe online download queue. */
@Repository
public class SqliteDownloadQueueAdapter implements DownloadQueuePort {
    private final JdbcTemplate jdbc;

    public SqliteDownloadQueueAdapter(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("flywayMetadata") Flyway ignoredMigrationDependency) {
        this.jdbc = jdbc;
        recoverInterrupted();
    }

    @Override
    public void markPending(String collectionId, String bookId, String physicalArchiveIdentity, String resumeInformation) {
        requireIdentity(collectionId, bookId);
        jdbc.update("""
                INSERT INTO online_download_queue
                    (collection_id, book_id, status, physical_archive_identity, resume_information, updated_at)
                VALUES (?, ?, 'PENDING', ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(collection_id, book_id) DO UPDATE SET
                    status='PENDING',
                    physical_archive_identity=excluded.physical_archive_identity,
                    resume_information=excluded.resume_information,
                    last_error=NULL,
                    updated_at=CURRENT_TIMESTAMP
                """, collectionId, bookId, nullIfBlank(physicalArchiveIdentity), nullIfBlank(resumeInformation));
    }

    @Override
    public void markInProgress(String collectionId, String bookId) {
        requireIdentity(collectionId, bookId);
        int updated = jdbc.update("""
                UPDATE online_download_queue
                SET status='IN_PROGRESS', retry_count=retry_count+1,
                    last_attempt=CURRENT_TIMESTAMP, last_error=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE collection_id=? AND book_id=?
                """, collectionId, bookId);
        if (updated == 0) {
            markPending(collectionId, bookId, null, null);
            jdbc.update("""
                    UPDATE online_download_queue
                    SET status='IN_PROGRESS', retry_count=retry_count+1,
                        last_attempt=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                    WHERE collection_id=? AND book_id=?
                    """, collectionId, bookId);
        }
    }

    @Override
    public void markCompleted(String collectionId, String bookId, Path destination) {
        updateTerminal(collectionId, bookId, DownloadQueueStatus.COMPLETED,
                destination == null ? null : destination.toAbsolutePath().normalize().toString(), null, null);
    }

    @Override
    public void markFailed(String collectionId, String bookId, String safeError, String resumeInformation) {
        updateTerminal(collectionId, bookId, DownloadQueueStatus.FAILED, null,
                SensitiveDataSanitizer.sanitizeText(truncate(safeError, 2000)), resumeInformation);
    }

    @Override
    public void markCancelled(String collectionId, String bookId, String resumeInformation) {
        updateTerminal(collectionId, bookId, DownloadQueueStatus.CANCELLED, null, null, resumeInformation);
    }

    private void updateTerminal(String collectionId, String bookId, DownloadQueueStatus status,
                                String destination, String error, String resumeInformation) {
        requireIdentity(collectionId, bookId);
        int updated = jdbc.update("""
                UPDATE online_download_queue
                SET status=?,
                    download_destination=COALESCE(?, download_destination),
                    last_error=?, resume_information=?, updated_at=CURRENT_TIMESTAMP
                WHERE collection_id=? AND book_id=?
                """, status.name(), nullIfBlank(destination), nullIfBlank(error), nullIfBlank(resumeInformation), collectionId, bookId);
        if (updated == 0) {
            markPending(collectionId, bookId, null, resumeInformation);
            jdbc.update("""
                    UPDATE online_download_queue
                    SET status=?, download_destination=?, last_error=?, resume_information=?, updated_at=CURRENT_TIMESTAMP
                    WHERE collection_id=? AND book_id=?
                    """, status.name(), nullIfBlank(destination), nullIfBlank(error), nullIfBlank(resumeInformation), collectionId, bookId);
        }
    }

    @Override
    public Optional<DownloadQueueEntry> find(String collectionId, String bookId) {
        requireIdentity(collectionId, bookId);
        List<DownloadQueueEntry> rows = jdbc.query("""
                SELECT collection_id, book_id, created_at, status, retry_count, last_attempt,
                       download_destination, physical_archive_identity, resume_information, last_error
                FROM online_download_queue WHERE collection_id=? AND book_id=?
                """, (rs, rowNum) -> map(rs.getString("collection_id"), rs.getString("book_id"),
                rs.getString("created_at"), rs.getString("status"), rs.getInt("retry_count"),
                rs.getString("last_attempt"), rs.getString("download_destination"),
                rs.getString("physical_archive_identity"), rs.getString("resume_information"), rs.getString("last_error")),
                collectionId, bookId);
        return rows.stream().findFirst();
    }

    @Override
    public List<DownloadQueueEntry> findByStatus(DownloadQueueStatus status, int limit) {
        if (status == null) throw new IllegalArgumentException("status is required");
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 10_000));
        return jdbc.query("""
                SELECT collection_id, book_id, created_at, status, retry_count, last_attempt,
                       download_destination, physical_archive_identity, resume_information, last_error
                FROM online_download_queue WHERE status=? ORDER BY updated_at, created_at LIMIT ?
                """, (rs, rowNum) -> map(rs.getString("collection_id"), rs.getString("book_id"),
                rs.getString("created_at"), rs.getString("status"), rs.getInt("retry_count"),
                rs.getString("last_attempt"), rs.getString("download_destination"),
                rs.getString("physical_archive_identity"), rs.getString("resume_information"), rs.getString("last_error")),
                status.name(), safeLimit);
    }

    @Override
    public int recoverInterrupted() {
        return jdbc.update("""
                UPDATE online_download_queue
                SET status='PENDING', last_error='Previous process stopped during download', updated_at=CURRENT_TIMESTAMP
                WHERE status='IN_PROGRESS'
                """);
    }

    private static DownloadQueueEntry map(String collectionId, String bookId, String createdAt, String status,
                                          int retryCount, String lastAttempt, String destination,
                                          String archiveIdentity, String resumeInformation, String lastError) {
        return new DownloadQueueEntry(collectionId, bookId, parseInstant(createdAt), DownloadQueueStatus.valueOf(status),
                retryCount, parseInstant(lastAttempt), destination, archiveIdentity, resumeInformation, lastError);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String normalized = value.contains("T") ? value : value.replace(' ', 'T') + "Z";
            return Instant.parse(normalized);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void requireIdentity(String collectionId, String bookId) {
        if (collectionId == null || collectionId.isBlank()) throw new IllegalArgumentException("collectionId is required");
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("bookId is required");
    }

    private static String nullIfBlank(String value) { return value == null || value.isBlank() ? null : value; }
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
