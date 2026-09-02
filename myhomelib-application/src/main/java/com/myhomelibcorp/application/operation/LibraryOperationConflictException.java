package com.myhomelibcorp.application.operation;

public final class LibraryOperationConflictException extends IllegalStateException {
    private final LibraryOperationType activeOperation;
    private final LibraryOperationType requestedOperation;

    public LibraryOperationConflictException(LibraryOperationType activeOperation,
                                             LibraryOperationType requestedOperation) {
        super("Операція " + requestedOperation + " недоступна, поки виконується " + activeOperation);
        this.activeOperation = activeOperation;
        this.requestedOperation = requestedOperation;
    }

    public LibraryOperationType activeOperation() {
        return activeOperation;
    }

    public LibraryOperationType requestedOperation() {
        return requestedOperation;
    }
}
