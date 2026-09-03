package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.progress.OperationStage;

/**
 * Structured failure exposed by online catalog update orchestration.
 * Keeps user-safe source/stage/version/rollback details without leaking credentials.
 */
public final class CatalogUpdateFailureException extends IllegalStateException {
    private final OperationStage stage;
    private final String source;
    private final String lastAppliedVersion;
    private final boolean mutationMayHaveCommitted;
    private final boolean rollbackAttempted;
    private final boolean rollbackSucceeded;

    public CatalogUpdateFailureException(
            String message,
            Throwable cause,
            OperationStage stage,
            String source,
            String lastAppliedVersion,
            boolean mutationMayHaveCommitted,
            boolean rollbackAttempted,
            boolean rollbackSucceeded) {
        super(message, cause);
        this.stage = stage == null ? OperationStage.FAILED : stage;
        this.source = source == null ? "" : source;
        this.lastAppliedVersion = lastAppliedVersion == null ? "" : lastAppliedVersion;
        this.mutationMayHaveCommitted = mutationMayHaveCommitted;
        this.rollbackAttempted = rollbackAttempted;
        this.rollbackSucceeded = rollbackSucceeded;
    }

    public OperationStage stage() { return stage; }
    public String source() { return source; }
    public String lastAppliedVersion() { return lastAppliedVersion; }
    public boolean mutationMayHaveCommitted() { return mutationMayHaveCommitted; }
    public boolean rollbackAttempted() { return rollbackAttempted; }
    public boolean rollbackSucceeded() { return rollbackSucceeded; }
}
