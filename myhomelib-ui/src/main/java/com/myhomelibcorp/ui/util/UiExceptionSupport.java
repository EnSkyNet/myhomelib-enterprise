package com.myhomelibcorp.ui.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Common exception helpers for asynchronous UI flows. */
public final class UiExceptionSupport {
    private UiExceptionSupport() { }

    public static Throwable unwrapAsync(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null
                && (current instanceof CompletionException || current instanceof ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
    public static String message(Throwable error) {
        Throwable cause = unwrapAsync(error);
        if (cause == null) return "Невідома помилка";
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

}
