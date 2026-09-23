package com.myhomelibcorp.organizer.fs;

import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable, validated view of the filesystem-side crash manifest. */
public record LibraryOperationCrashSnapshot(int schemaVersion, UUID operationId, String profileId,
                                            Path sourceRoot, Path destinationRoot,
                                            OrganizationOperationState state, String message,
                                            Instant updatedAt, List<Item> items) {
    public LibraryOperationCrashSnapshot {
        profileId = profileId == null ? "" : profileId;
        message = message == null ? "" : message;
        items = List.copyOf(items == null ? List.of() : items);
    }

    public record Item(UUID itemId, String action, Path sourcePath, String sourceEntry, Path targetPath,
                       OrganizationItemState state, String sourceSha256, String targetSha256,
                       Path backupPath, String message) {
        public Item {
            action = action == null ? "" : action;
            sourceEntry = sourceEntry == null ? "" : sourceEntry;
            sourceSha256 = sourceSha256 == null ? "" : sourceSha256;
            targetSha256 = targetSha256 == null ? "" : targetSha256;
            message = message == null ? "" : message;
        }
    }
}
