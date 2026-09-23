package com.myhomelibcorp.organizer.journal;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

public record OrganizationJournalOperation(UUID operationId, String profileId, Path sourceRoot, Path destinationRoot,
                                           OrganizationOperationState state, Instant startedAt, Instant completedAt,
                                           String summaryJson, String collectionId) {
    public OrganizationJournalOperation {
        collectionId = collectionId == null ? "" : collectionId.trim();
        summaryJson = summaryJson == null ? "" : summaryJson;
    }

    /** Compatibility constructor for historical journal/recovery callers without collection identity. */
    public OrganizationJournalOperation(UUID operationId, String profileId, Path sourceRoot, Path destinationRoot,
                                        OrganizationOperationState state, Instant startedAt, Instant completedAt,
                                        String summaryJson) {
        this(operationId, profileId, sourceRoot, destinationRoot, state, startedAt, completedAt, summaryJson, "");
    }
}
