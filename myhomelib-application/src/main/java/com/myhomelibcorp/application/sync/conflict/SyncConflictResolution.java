package com.myhomelibcorp.application.sync.conflict;

import com.myhomelibcorp.domain.model.sync.SyncRecord;

import java.util.Objects;
import java.util.Optional;

/** Resolution result that always retains both original records for audit/review. */
public record SyncConflictResolution(
        SyncRecord local,
        SyncRecord remote,
        ConflictResolutionDecision decision,
        SyncRecord resolved,
        String reason
) {
    public SyncConflictResolution {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(decision, "decision");
        reason = reason == null ? "" : reason;
        if (decision == ConflictResolutionDecision.MANUAL && resolved != null) {
            throw new IllegalArgumentException("manual conflict must not have an automatic resolved record");
        }
        if (decision != ConflictResolutionDecision.MANUAL && resolved == null) {
            throw new IllegalArgumentException("automatic resolution requires a resolved record");
        }
    }

    public boolean requiresManualReview() {
        return decision == ConflictResolutionDecision.MANUAL;
    }

    public Optional<SyncRecord> resolvedRecord() {
        return Optional.ofNullable(resolved);
    }
}
