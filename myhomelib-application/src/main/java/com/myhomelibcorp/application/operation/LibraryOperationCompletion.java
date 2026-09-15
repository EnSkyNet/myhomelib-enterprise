package com.myhomelibcorp.application.operation;

/** Framework-neutral terminal event for one root library operation. */
public record LibraryOperationCompletion(
        LibraryOperationType operation,
        LibraryOperationOutcome outcome,
        String detail
) {
    public LibraryOperationCompletion {
        if (operation == null) throw new IllegalArgumentException("operation is required");
        outcome = outcome == null ? LibraryOperationOutcome.COMPLETED : outcome;
        detail = detail == null ? "" : detail;
    }
}
