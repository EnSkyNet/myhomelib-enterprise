package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.duplicate.ArtifactScanCandidate;
import com.myhomelibcorp.application.duplicate.ExactDuplicateArtifact;
import com.myhomelibcorp.application.duplicate.ExactDuplicateGroup;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanSession;
import com.myhomelibcorp.application.port.out.duplicate.ExactDuplicateScanStore;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SqliteExactDuplicateScanStore implements ExactDuplicateScanStore {
    private final CollectionManager collections;

    private JdbcTemplate jdbc() {
        return collections.getCurrentJdbcTemplate();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(new DataSourceTransactionManager(collections.getCurrentDataSource()));
    }

    @Override
    public ExactDuplicateScanSession resumeOrStart() {
        List<ExactDuplicateScanSession> existing = jdbc().query("""
                SELECT scan_id,total,processed,hashed,skipped,bytes_processed
                  FROM artifact_duplicate_scans
                 WHERE completed_at IS NULL
                 ORDER BY started_at DESC
                 LIMIT 1
                """, (rs, rowNum) -> session(rs.getString("scan_id"), rs.getLong("total"),
                rs.getLong("processed"), rs.getLong("hashed"), rs.getLong("skipped"),
                rs.getLong("bytes_processed"), true));
        if (!existing.isEmpty()) {
            jdbc().update("UPDATE artifact_duplicate_scans SET status='RUNNING', updated_at=CURRENT_TIMESTAMP WHERE scan_id=?",
                    existing.getFirst().scanId());
            return existing.getFirst();
        }

        return tx().execute(status -> {
            String scanId = UUID.randomUUID().toString();
            jdbc().update("INSERT INTO artifact_duplicate_scans(scan_id,status) VALUES (?, 'RUNNING')", scanId);
            jdbc().update("""
                    INSERT INTO artifact_duplicate_scan_queue(scan_id,artifact_id)
                    SELECT ?, ba.artifact_id
                      FROM book_artifacts ba
                      JOIN books b ON b.id = ba.book_id
                     WHERE ba.local = 1
                       AND ba.state = 'AVAILABLE'
                       AND b.deleted = 0
                     ORDER BY ba.artifact_id
                    """, scanId);
            Long total = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM artifact_duplicate_scan_queue WHERE scan_id=?", Long.class, scanId);
            long count = total == null ? 0 : total;
            jdbc().update("UPDATE artifact_duplicate_scans SET total=?, updated_at=CURRENT_TIMESTAMP WHERE scan_id=?",
                    count, scanId);
            return session(scanId, count, 0, 0, 0, 0, false);
        });
    }

    @Override
    public List<ArtifactScanCandidate> nextBatch(String scanId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 1000));
        skipOrphanedCandidates(scanId);
        return jdbc().query("""
                SELECT ba.book_id, ba.artifact_id, COALESCE(ba.file_format,''),
                       COALESCE(NULLIF(ba.archive_name,''), ba.file_name, ''),
                       COALESCE(ba.folder, b.folder, ''), COALESCE(ba.archive_entry,''),
                       COALESCE(ba.size_bytes,0), COALESCE(ba.collection_root,b.collection_root,'')
                  FROM artifact_duplicate_scan_queue q
                  JOIN book_artifacts ba ON ba.artifact_id = q.artifact_id
                  JOIN books b ON b.id = ba.book_id
                 WHERE q.scan_id=? AND q.processed_at IS NULL
                 ORDER BY q.artifact_id
                 LIMIT ?
                """, (rs, rowNum) -> new ArtifactScanCandidate(
                BookId.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                new BookFile(rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8))),
                scanId, boundedLimit);
    }

    private void skipOrphanedCandidates(String scanId) {
        tx().executeWithoutResult(status -> {
            int orphaned = jdbc().update("""
                    UPDATE artifact_duplicate_scan_queue
                       SET processed_at=CURRENT_TIMESTAMP, skip_reason='artifact removed during scan'
                     WHERE scan_id=? AND processed_at IS NULL
                       AND NOT EXISTS (
                           SELECT 1 FROM book_artifacts ba
                            WHERE ba.artifact_id=artifact_duplicate_scan_queue.artifact_id
                       )
                    """, scanId);
            if (orphaned > 0) {
                jdbc().update("""
                        UPDATE artifact_duplicate_scans
                           SET processed=processed+?, skipped=skipped+?, updated_at=CURRENT_TIMESTAMP
                         WHERE scan_id=?
                        """, orphaned, orphaned, scanId);
            }
        });
    }

    @Override
    public ExactDuplicateScanSession markHashed(String scanId, String artifactId, String sha256, long sizeBytes) {
        if (sha256 == null || sha256.isBlank()) throw new IllegalArgumentException("sha256 is required");
        long safeSize = Math.max(0, sizeBytes);
        tx().executeWithoutResult(status -> {
            int queueUpdated = jdbc().update("""
                    UPDATE artifact_duplicate_scan_queue
                       SET processed_at=CURRENT_TIMESTAMP, skip_reason=NULL
                     WHERE scan_id=? AND artifact_id=? AND processed_at IS NULL
                    """, scanId, artifactId);
            if (queueUpdated == 0) return;
            jdbc().update("""
                    UPDATE book_artifacts
                       SET sha256=?, size_bytes=?, updated_at=CURRENT_TIMESTAMP
                     WHERE artifact_id=?
                    """, sha256, safeSize, artifactId);
            jdbc().update("""
                    UPDATE artifact_duplicate_scans
                       SET processed=processed+1, hashed=hashed+1,
                           bytes_processed=bytes_processed+?, updated_at=CURRENT_TIMESTAMP
                     WHERE scan_id=?
                    """, safeSize, scanId);
        });
        return load(scanId);
    }

    @Override
    public ExactDuplicateScanSession markSkipped(String scanId, String artifactId, String reason) {
        String safeReason = reason == null ? "unreadable" : reason;
        tx().executeWithoutResult(status -> {
            int queueUpdated = jdbc().update("""
                    UPDATE artifact_duplicate_scan_queue
                       SET processed_at=CURRENT_TIMESTAMP, skip_reason=?
                     WHERE scan_id=? AND artifact_id=? AND processed_at IS NULL
                    """, safeReason, scanId, artifactId);
            if (queueUpdated == 0) return;
            jdbc().update("""
                    UPDATE artifact_duplicate_scans
                       SET processed=processed+1, skipped=skipped+1, updated_at=CURRENT_TIMESTAMP
                     WHERE scan_id=?
                    """, scanId);
        });
        return load(scanId);
    }

    @Override
    public ExactDuplicateScanSession pause(String scanId) {
        jdbc().update("""
                UPDATE artifact_duplicate_scans
                   SET status='PAUSED', updated_at=CURRENT_TIMESTAMP
                 WHERE scan_id=? AND completed_at IS NULL
                """, scanId);
        return load(scanId);
    }

    @Override
    public ExactDuplicateScanSession complete(String scanId) {
        Long pending = jdbc().queryForObject("""
                SELECT COUNT(*) FROM artifact_duplicate_scan_queue
                 WHERE scan_id=? AND processed_at IS NULL
                """, Long.class, scanId);
        if (pending != null && pending > 0) {
            throw new IllegalStateException("Cannot complete exact duplicate scan with pending artifacts: " + pending);
        }
        jdbc().update("""
                UPDATE artifact_duplicate_scans
                   SET status='COMPLETED', completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                 WHERE scan_id=?
                """, scanId);
        return load(scanId);
    }

    @Override
    public List<ExactDuplicateGroup> findDuplicateGroups() {
        Map<String, List<ExactDuplicateArtifact>> byHash = new LinkedHashMap<>();
        jdbc().query("""
                SELECT ba.sha256, ba.book_id, ba.artifact_id, b.title, COALESCE(ba.file_format,''),
                       COALESCE(NULLIF(ba.archive_name,''), ba.file_name, ''),
                       COALESCE(ba.folder,b.folder,''), COALESCE(ba.archive_entry,''),
                       COALESCE(ba.size_bytes,0), COALESCE(ba.collection_root,b.collection_root,'')
                  FROM book_artifacts ba
                  JOIN books b ON b.id=ba.book_id
                 WHERE ba.local=1 AND ba.state='AVAILABLE' AND b.deleted=0
                   AND ba.sha256 IS NOT NULL AND trim(ba.sha256)<>''
                   AND ba.sha256 IN (
                       SELECT sha256 FROM book_artifacts
                        WHERE local=1 AND state='AVAILABLE' AND sha256 IS NOT NULL AND trim(sha256)<>''
                        GROUP BY sha256 HAVING COUNT(*) > 1
                   )
                 ORDER BY ba.sha256, ba.book_id, ba.artifact_id
                """, rs -> {
            String sha = rs.getString(1);
            ExactDuplicateArtifact artifact = new ExactDuplicateArtifact(
                    BookId.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5),
                    new BookFile(rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getString(10)),
                    rs.getLong(9));
            byHash.computeIfAbsent(sha, ignored -> new ArrayList<>()).add(artifact);
        });
        return byHash.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> new ExactDuplicateGroup(entry.getKey(), entry.getValue()))
                .toList();
    }

    private ExactDuplicateScanSession load(String scanId) {
        return jdbc().queryForObject("""
                SELECT scan_id,total,processed,hashed,skipped,bytes_processed,
                       CASE WHEN status='PAUSED' THEN 1 ELSE 0 END AS resumed_marker
                  FROM artifact_duplicate_scans WHERE scan_id=?
                """, (rs, rowNum) -> session(rs.getString("scan_id"), rs.getLong("total"),
                rs.getLong("processed"), rs.getLong("hashed"), rs.getLong("skipped"),
                rs.getLong("bytes_processed"), rs.getInt("resumed_marker") == 1), scanId);
    }

    private static ExactDuplicateScanSession session(String id, long total, long processed, long hashed,
                                                     long skipped, long bytes, boolean resumed) {
        return new ExactDuplicateScanSession(id, total, processed, hashed, skipped, bytes, resumed);
    }
}
