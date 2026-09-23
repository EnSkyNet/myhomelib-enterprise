package com.myhomelibcorp.organizer.apply;

import java.util.List;
import java.util.UUID;

public record ApplyResult(UUID operationId, int committed, int converted, int moved, int skipped, int conflicts, int failed,
                          boolean cancelled, int optimizedEpubs, long optimizationSourceBytes, long optimizationResultBytes,
                          long optimizationSavedBytes, List<String> warnings) {
    public ApplyResult {
        committed = Math.max(0, committed);
        converted = Math.max(0, converted);
        moved = Math.max(0, moved);
        skipped = Math.max(0, skipped);
        conflicts = Math.max(0, conflicts);
        failed = Math.max(0, failed);
        optimizedEpubs = Math.max(0, optimizedEpubs);
        optimizationSourceBytes = Math.max(0L, optimizationSourceBytes);
        optimizationResultBytes = Math.max(0L, optimizationResultBytes);
        optimizationSavedBytes = Math.max(0L, optimizationSavedBytes);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public double optimizationSavedPercent() {
        return optimizationSourceBytes <= 0 ? 0.0 : optimizationSavedBytes * 100.0 / optimizationSourceBytes;
    }
}
