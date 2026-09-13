package com.myhomelibcorp.application.health;

import com.myhomelibcorp.application.integrity.ArtifactIntegrityFinding;

import java.time.Instant;
import java.util.List;

/** Unified user-facing library health snapshot. */
public record LibraryHealthReport(
        Instant generatedAt,
        long localArtifacts,
        long missingArtifacts,
        long corruptArtifacts,
        long changedArtifacts,
        long duplicateBooks,
        long metadataGaps,
        boolean databaseHealthy,
        boolean searchIndexFresh,
        String searchIndexDetail,
        long catalogBooks,
        long indexedDocuments,
        Instant latestBackupAt,
        long backupAgeHours,
        boolean backupStale,
        long auditInspected,
        long auditReused,
        long auditBytesRead,
        List<LibraryHealthIssue> issues,
        List<ArtifactIntegrityFinding> artifactFindings
) {
    public LibraryHealthReport {
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        searchIndexDetail = searchIndexDetail == null ? "" : searchIndexDetail;
        issues = issues == null ? List.of() : List.copyOf(issues);
        artifactFindings = artifactFindings == null ? List.of() : List.copyOf(artifactFindings);
    }

    public long totalProblemCount() {
        return missingArtifacts + corruptArtifacts + changedArtifacts + duplicateBooks + metadataGaps
                + (databaseHealthy ? 0 : 1) + (searchIndexFresh ? 0 : 1) + (backupStale ? 1 : 0);
    }

    public boolean healthy() {
        return totalProblemCount() == 0;
    }
}
