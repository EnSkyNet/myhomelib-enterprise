package com.myhomelibcorp.application.progress;

/**
 * Immutable telemetry for long-running operations. Counts are monotonic within an operation where applicable.
 * A total/bytesTotal value below zero means that the total is not known yet.
 */
public record OperationProgress(
        String operationId,
        OperationStage stage,
        long processed,
        long total,
        long bytesProcessed,
        long bytesTotal,
        long inserted,
        long updated,
        long deleted,
        long skipped,
        long duplicates,
        long warnings,
        long errors,
        String currentItem,
        boolean cancellable
) {
    public OperationProgress {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId is required");
        stage = stage == null ? OperationStage.IMPORTING : stage;
        currentItem = currentItem == null ? "" : currentItem;
    }

    public double fraction() {
        if (total <= 0) return -1.0;
        return Math.max(0.0, Math.min(1.0, (double) processed / (double) total));
    }

    public static OperationProgress stage(String operationId, OperationStage stage, boolean cancellable) {
        return new OperationProgress(operationId, stage, 0, -1, 0, -1,
                0, 0, 0, 0, 0, 0, 0, "", cancellable);
    }

    public OperationProgress withProgress(long processed, long total) {
        return new OperationProgress(operationId, stage, processed, total, bytesProcessed, bytesTotal,
                inserted, updated, deleted, skipped, duplicates, warnings, errors, currentItem, cancellable);
    }

    public OperationProgress withBytes(long bytesProcessed, long bytesTotal) {
        return new OperationProgress(operationId, stage, processed, total, bytesProcessed, bytesTotal,
                inserted, updated, deleted, skipped, duplicates, warnings, errors, currentItem, cancellable);
    }

    public OperationProgress withCounts(long inserted, long updated, long deleted, long skipped,
                                        long duplicates, long warnings, long errors) {
        return new OperationProgress(operationId, stage, processed, total, bytesProcessed, bytesTotal,
                inserted, updated, deleted, skipped, duplicates, warnings, errors, currentItem, cancellable);
    }

    public OperationProgress withCurrentItem(String currentItem) {
        return new OperationProgress(operationId, stage, processed, total, bytesProcessed, bytesTotal,
                inserted, updated, deleted, skipped, duplicates, warnings, errors, currentItem, cancellable);
    }
}
