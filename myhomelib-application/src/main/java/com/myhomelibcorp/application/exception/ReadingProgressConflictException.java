package com.myhomelibcorp.application.exception;

/** Concurrent reading-position update conflict; caller must reload canonical state. */
public class ReadingProgressConflictException extends IllegalStateException {
    public ReadingProgressConflictException(String message) {
        super(message);
    }
}
