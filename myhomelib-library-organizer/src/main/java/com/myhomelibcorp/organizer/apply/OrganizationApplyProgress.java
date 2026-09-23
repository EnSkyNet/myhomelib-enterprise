package com.myhomelibcorp.organizer.apply;

import java.nio.file.Path;

/** Progress snapshot for Organizer Apply. */
public record OrganizationApplyProgress(int total,
                                        int processed,
                                        int committed,
                                        int converted,
                                        int moved,
                                        int skipped,
                                        int conflicts,
                                        int failed,
                                        Path currentPath) {
    public OrganizationApplyProgress {
        total = Math.max(0, total);
        processed = Math.max(0, processed);
        committed = Math.max(0, committed);
        converted = Math.max(0, converted);
        moved = Math.max(0, moved);
        skipped = Math.max(0, skipped);
        conflicts = Math.max(0, conflicts);
        failed = Math.max(0, failed);
    }

    /** Compatibility constructor for callers interested only in aggregate progress. */
    public OrganizationApplyProgress(int total, int processed, int committed, int skipped, int failed, Path currentPath) {
        this(total, processed, committed, 0, 0, skipped, 0, failed, currentPath);
    }
}
