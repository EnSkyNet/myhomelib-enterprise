package com.myhomelibcorp.application.duplicate;

import java.util.List;

/** Final or paused result of an exact duplicate scan. */
public record ExactDuplicateScanResult(
        String scanId,
        long total,
        long processed,
        long hashed,
        long skipped,
        long bytesProcessed,
        boolean cancelled,
        boolean resumed,
        List<ExactDuplicateGroup> groups
) {
    public ExactDuplicateScanResult {
        groups = List.copyOf(groups == null ? List.of() : groups);
    }

    public long duplicateArtifacts() {
        return groups.stream().mapToLong(group -> group.artifacts().size()).sum();
    }
}
