package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationStage;

import java.time.Duration;
import java.time.Instant;

/** Immutable UI projection of one long-running application operation. */
public record OperationCenterEntry(
        String operationId,
        String title,
        String collectionId,
        OperationKind kind,
        OperationStage stage,
        long processed,
        long total,
        long inserted,
        long updated,
        long deleted,
        long skipped,
        long duplicates,
        long warnings,
        long errors,
        String currentItem,
        boolean cancellable,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt,
        String errorMessage
) {
    public OperationCenterEntry {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId is required");
        title = title == null || title.isBlank() ? "Операція" : title;
        collectionId = collectionId == null ? "" : collectionId;
        kind = kind == null ? OperationKind.GENERIC : kind;
        stage = stage == null ? OperationStage.IMPORTING : stage;
        currentItem = currentItem == null ? "" : currentItem;
        startedAt = startedAt == null ? Instant.now() : startedAt;
        updatedAt = updatedAt == null ? startedAt : updatedAt;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public boolean active() {
        return stage != OperationStage.COMPLETED && stage != OperationStage.CANCELLED && stage != OperationStage.FAILED;
    }

    public double fraction() {
        if (total <= 0) return -1.0;
        return Math.max(0.0, Math.min(1.0, (double) processed / (double) total));
    }

    public Duration duration(Instant now) {
        Instant end = finishedAt != null ? finishedAt : (now == null ? Instant.now() : now);
        return Duration.between(startedAt, end).isNegative() ? Duration.ZERO : Duration.between(startedAt, end);
    }
}
