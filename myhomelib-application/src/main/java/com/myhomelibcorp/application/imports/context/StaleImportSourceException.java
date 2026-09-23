package com.myhomelibcorp.application.imports.context;

/**
 * Signals that an import source no longer matches the immutable watcher observation that queued it.
 * Callers should re-observe/re-fingerprint the source instead of importing the changed bytes.
 */
public final class StaleImportSourceException extends IllegalStateException {
    public StaleImportSourceException(String message) { super(message); }
    public StaleImportSourceException(String message, Throwable cause) { super(message, cause); }
}
