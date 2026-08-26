package com.myhomelibcorp.application.collection;

import java.time.Instant;
import java.util.List;

public record CollectionMaintenanceReport(
        String collectionId,
        Instant generatedAt,
        boolean databaseIntegrityOk,
        String databaseIntegrityMessage,
        long scannedBooks,
        long scannedFiles,
        long missingFiles,
        long invalidArchiveReferences,
        long orphanFiles,
        long orphanedAuthors,
        long orphanedGenres,
        long duplicateBooks,
        boolean samplesTruncated,
        List<MaintenanceIssue> issues
) {
    public CollectionMaintenanceReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public long totalIssues() {
        long database = databaseIntegrityOk ? 0 : 1;
        return database + missingFiles + invalidArchiveReferences + orphanFiles
                + orphanedAuthors + orphanedGenres + duplicateBooks;
    }

    public long repairableSamples() {
        return issues.stream().filter(MaintenanceIssue::repairable).count();
    }

    public boolean hasIssues() {
        return totalIssues() > 0;
    }
}
