package com.myhomelibcorp.infrastructure.integrity;

import com.myhomelibcorp.application.integrity.ArtifactIntegrityFinding;
import com.myhomelibcorp.application.integrity.ArtifactIntegrityReport;
import com.myhomelibcorp.application.integrity.ArtifactIntegrityStatus;
import com.myhomelibcorp.application.port.out.integrity.ArtifactIntegrityPort;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static com.myhomelibcorp.shared.util.ThrowableMessages.rootMessage;

/**
 * MHL-116 implementation. The audit never deletes, repairs, or rewrites book_artifacts.
 * It persists only its own baseline/cache so unchanged physical files can skip content hashing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArtifactIntegrityService implements ArtifactIntegrityPort {
    private static final int BATCH_SIZE = 256;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final CollectionManager collections;
    private final BookResourcePort resources;

    private JdbcTemplate jdbc() {
        return collections.getCurrentJdbcTemplate();
    }

    @Override
    public ArtifactIntegrityReport auditIncremental(int detailLimit) {
        int boundedDetailLimit = Math.max(0, Math.min(detailLimit, 10_000));
        long total = countCandidates();
        long inspected = 0L;
        long reused = 0L;
        long healthy = 0L;
        long missing = 0L;
        long corrupt = 0L;
        long changed = 0L;
        long bytesRead = 0L;
        List<ArtifactIntegrityFinding> findings = new ArrayList<>(Math.min(boundedDetailLimit, 256));

        String afterArtifactId = "";
        while (true) {
            List<Candidate> batch = loadBatch(afterArtifactId, BATCH_SIZE);
            if (batch.isEmpty()) break;
            for (Candidate candidate : batch) {
                AuditOutcome outcome = inspect(candidate);
                inspected++;
                if (outcome.reused()) reused++;
                bytesRead += Math.max(0L, outcome.bytesRead());
                switch (outcome.status()) {
                    case OK -> healthy++;
                    case MISSING -> missing++;
                    case UNREADABLE, CORRUPT_ARCHIVE -> corrupt++;
                    case SIZE_CHANGED, HASH_CHANGED -> changed++;
                }
                if (outcome.status().isIssue() && findings.size() < boundedDetailLimit) {
                    findings.add(new ArtifactIntegrityFinding(
                            candidate.artifactId(), candidate.bookId(), candidate.bookTitle(),
                            outcome.path(), outcome.status(), outcome.baselineSize(), outcome.observedSize(),
                            outcome.baselineSha(), outcome.observedSha(), outcome.detail(), Instant.now()));
                }
                persist(candidate, outcome);
                afterArtifactId = candidate.artifactId();
            }
        }

        removeInapplicableState();
        return new ArtifactIntegrityReport(total, inspected, reused, healthy, missing, corrupt, changed,
                bytesRead, findings, Instant.now());
    }

    private AuditOutcome inspect(Candidate candidate) {
        Path physical = physicalPath(candidate);
        String displayPath = physical == null ? "" : physical.toAbsolutePath().normalize().toString();
        if (!candidate.archiveEntry().isBlank() && !displayPath.isBlank()) {
            displayPath = displayPath + "!/" + candidate.archiveEntry();
        }
        if (physical == null || !Files.isRegularFile(physical)) {
            return missing(candidate, displayPath, "Фізичний файл або архів не знайдено");
        }

        try {
            long physicalSize = Files.size(physical);
            long physicalMtime = Files.getLastModifiedTime(physical).toMillis();
            Baseline baseline = effectiveBaseline(candidate);

            if (candidate.hasState()
                    && candidate.lastPhysicalSize() == physicalSize
                    && candidate.lastModifiedMillis() == physicalMtime
                    && candidate.observedSize() >= 0
                    && candidate.observedSha() != null && !candidate.observedSha().isBlank()) {
                Verdict verdict = compare(baseline, candidate.observedSize(), candidate.observedSha());
                return new AuditOutcome(verdict.status(), displayPath, baseline.size(), candidate.observedSize(),
                        baseline.sha(), candidate.observedSha(), verdict.detail(), physicalSize, physicalMtime, 0L, true);
            }

            if (resources.isArchive(physical.toString())) {
                try {
                    resources.listArchiveEntries(physical);
                } catch (RuntimeException archiveFailure) {
                    return new AuditOutcome(ArtifactIntegrityStatus.CORRUPT_ARCHIVE, displayPath,
                            baseline.size(), -1L, baseline.sha(), "", rootMessage(archiveFailure),
                            physicalSize, physicalMtime, 0L, false);
                }
            }

            Observed observed = readAndHash(candidate);
            Verdict verdict = compare(baseline.withFallback(observed), observed.size(), observed.sha256());
            Baseline persistedBaseline = baseline.withFallback(observed);
            return new AuditOutcome(verdict.status(), displayPath,
                    persistedBaseline.size(), observed.size(), persistedBaseline.sha(), observed.sha256(),
                    verdict.detail(), physicalSize, physicalMtime, observed.size(), false);
        } catch (UncheckedIOException error) {
            return unreadable(candidate, displayPath, rootMessage(error));
        } catch (IOException | RuntimeException error) {
            return unreadable(candidate, displayPath, rootMessage(error));
        }
    }

    private Observed readAndHash(Candidate candidate) throws IOException {
        try {
            var optional = resources.readBookData(candidate.fileName(), candidate.folder(),
                    candidate.collectionRoot(), candidate.archiveEntry());
            if (optional.isEmpty()) {
                throw new IOException(candidate.archiveEntry().isBlank()
                        ? "Локальний ресурс не відкривається"
                        : "Потрібний запис в архіві не знайдено або не відкривається");
            }
            try (InputStream in = optional.get()) {
                MessageDigest digest = sha256();
                byte[] buffer = new byte[BUFFER_SIZE];
                long size = 0L;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    digest.update(buffer, 0, read);
                    size += read;
                }
                return new Observed(size, HexFormat.of().formatHex(digest.digest()));
            }
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private Path physicalPath(Candidate candidate) {
        String fileName = candidate.fileName();
        String folder = candidate.folder();
        String root = candidate.collectionRoot();
        if (resources.isArchive(folder)) {
            return resources.buildFilePath(root, null, folder);
        }
        return resources.buildFilePath(root, folder, fileName);
    }

    private AuditOutcome missing(Candidate candidate, String path, String detail) {
        Baseline baseline = effectiveBaseline(candidate);
        return new AuditOutcome(ArtifactIntegrityStatus.MISSING, path, baseline.size(), -1L,
                baseline.sha(), "", detail, -1L, -1L, 0L, false);
    }

    private AuditOutcome unreadable(Candidate candidate, String path, String detail) {
        Baseline baseline = effectiveBaseline(candidate);
        long physicalSize = -1L;
        long physicalMtime = -1L;
        try {
            Path physical = physicalPath(candidate);
            if (physical != null && Files.isRegularFile(physical)) {
                physicalSize = Files.size(physical);
                physicalMtime = Files.getLastModifiedTime(physical).toMillis();
            }
        } catch (IOException ignored) { }
        return new AuditOutcome(ArtifactIntegrityStatus.UNREADABLE, path, baseline.size(), -1L,
                baseline.sha(), "", detail, physicalSize, physicalMtime, 0L, false);
    }

    private Baseline effectiveBaseline(Candidate candidate) {
        long size = candidate.catalogSize() >= 0 ? candidate.catalogSize() : candidate.baselineSize();
        String sha = nonBlank(candidate.catalogSha()) ? candidate.catalogSha() : candidate.baselineSha();
        return new Baseline(size >= 0 ? size : -1L, sha == null ? "" : sha);
    }

    private Verdict compare(Baseline baseline, long observedSize, String observedSha) {
        if (nonBlank(baseline.sha()) && nonBlank(observedSha)
                && !baseline.sha().equalsIgnoreCase(observedSha)) {
            return new Verdict(ArtifactIntegrityStatus.HASH_CHANGED, "SHA-256 відрізняється від baseline");
        }
        if (baseline.size() >= 0 && observedSize >= 0 && baseline.size() != observedSize) {
            return new Verdict(ArtifactIntegrityStatus.SIZE_CHANGED,
                    "Розмір відрізняється від baseline: " + baseline.size() + " → " + observedSize + " байт");
        }
        return new Verdict(ArtifactIntegrityStatus.OK, "");
    }

    private long countCandidates() {
        Long count = jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM book_artifacts ba
                  JOIN books b ON b.id=ba.book_id
                 WHERE ba.local=1 AND ba.state='AVAILABLE' AND COALESCE(b.deleted,0)=0
                """, Long.class);
        return count == null ? 0L : count;
    }

    private List<Candidate> loadBatch(String afterArtifactId, int limit) {
        return jdbc().query("""
                SELECT ba.artifact_id, ba.book_id, COALESCE(b.title,''),
                       COALESCE(NULLIF(ba.archive_name,''), ba.file_name, ''),
                       COALESCE(ba.folder,b.folder,''), COALESCE(ba.archive_entry,''),
                       COALESCE(ba.collection_root,b.collection_root,''),
                       COALESCE(ba.size_bytes,-1), COALESCE(ba.sha256,''),
                       s.baseline_size_bytes, COALESCE(s.baseline_sha256,''),
                       COALESCE(s.last_physical_size_bytes,-1), COALESCE(s.last_modified_millis,-1),
                       COALESCE(s.observed_size_bytes,-1), COALESCE(s.observed_sha256,''), s.status
                  FROM book_artifacts ba
                  JOIN books b ON b.id=ba.book_id
                  LEFT JOIN artifact_integrity_state s ON s.artifact_id=ba.artifact_id
                 WHERE ba.local=1 AND ba.state='AVAILABLE' AND COALESCE(b.deleted,0)=0
                   AND ba.artifact_id > ?
                 ORDER BY ba.artifact_id
                 LIMIT ?
                """, (rs, rowNum) -> new Candidate(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9),
                nullableLong(rs.getObject(10)), rs.getString(11), rs.getLong(12), rs.getLong(13),
                rs.getLong(14), rs.getString(15), rs.getString(16) != null), afterArtifactId, limit);
    }

    private void persist(Candidate candidate, AuditOutcome outcome) {
        Long baselineSize = outcome.baselineSize() >= 0 ? outcome.baselineSize() : null;
        String baselineSha = nonBlank(outcome.baselineSha()) ? outcome.baselineSha() : null;
        String observedSha = nonBlank(outcome.observedSha()) ? outcome.observedSha() : null;
        jdbc().update("""
                INSERT INTO artifact_integrity_state(
                    artifact_id, baseline_size_bytes, baseline_sha256,
                    last_physical_size_bytes, last_modified_millis,
                    observed_size_bytes, observed_sha256, status, detail, checked_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(artifact_id) DO UPDATE SET
                    baseline_size_bytes=excluded.baseline_size_bytes,
                    baseline_sha256=excluded.baseline_sha256,
                    last_physical_size_bytes=excluded.last_physical_size_bytes,
                    last_modified_millis=excluded.last_modified_millis,
                    observed_size_bytes=excluded.observed_size_bytes,
                    observed_sha256=excluded.observed_sha256,
                    status=excluded.status,
                    detail=excluded.detail,
                    checked_at=excluded.checked_at
                """, candidate.artifactId(), baselineSize, baselineSha,
                outcome.physicalSize(), outcome.physicalMtime(), outcome.observedSize(), observedSha,
                outcome.status().name(), outcome.detail(), Instant.now().toString());
    }

    private void removeInapplicableState() {
        jdbc().update("""
                DELETE FROM artifact_integrity_state
                 WHERE NOT EXISTS (
                       SELECT 1 FROM book_artifacts ba
                       JOIN books b ON b.id=ba.book_id
                        WHERE ba.artifact_id=artifact_integrity_state.artifact_id
                          AND ba.local=1 AND ba.state='AVAILABLE' AND COALESCE(b.deleted,0)=0
                 )
                """);
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }


    private record Candidate(
            String artifactId,
            String bookId,
            String bookTitle,
            String fileName,
            String folder,
            String archiveEntry,
            String collectionRoot,
            long catalogSize,
            String catalogSha,
            Long baselineSizeNullable,
            String baselineSha,
            long lastPhysicalSize,
            long lastModifiedMillis,
            long observedSize,
            String observedSha,
            boolean hasState
    ) {
        long baselineSize() { return baselineSizeNullable == null ? -1L : baselineSizeNullable; }
    }

    private record Baseline(long size, String sha) {
        Baseline withFallback(Observed observed) {
            long nextSize = size >= 0 ? size : observed.size();
            String nextSha = nonBlank(sha) ? sha : observed.sha256();
            return new Baseline(nextSize, nextSha);
        }
    }

    private record Observed(long size, String sha256) { }
    private record Verdict(ArtifactIntegrityStatus status, String detail) { }
    private record AuditOutcome(
            ArtifactIntegrityStatus status,
            String path,
            long baselineSize,
            long observedSize,
            String baselineSha,
            String observedSha,
            String detail,
            long physicalSize,
            long physicalMtime,
            long bytesRead,
            boolean reused
    ) { }
}
