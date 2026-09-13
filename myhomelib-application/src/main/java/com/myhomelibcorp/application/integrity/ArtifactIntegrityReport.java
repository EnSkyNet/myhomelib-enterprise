package com.myhomelibcorp.application.integrity;

import java.time.Instant;
import java.util.List;

/** Summary of an incremental artifact audit. Findings contain only bounded issue details. */
public record ArtifactIntegrityReport(
        long totalArtifacts,
        long inspectedArtifacts,
        long reusedArtifacts,
        long healthyArtifacts,
        long missingArtifacts,
        long corruptArtifacts,
        long changedArtifacts,
        long bytesRead,
        List<ArtifactIntegrityFinding> findings,
        Instant completedAt
) {
    public ArtifactIntegrityReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        completedAt = completedAt == null ? Instant.now() : completedAt;
    }

    public long issueCount() {
        return missingArtifacts + corruptArtifacts + changedArtifacts;
    }

    public boolean hasIssues() {
        return issueCount() > 0;
    }
}
