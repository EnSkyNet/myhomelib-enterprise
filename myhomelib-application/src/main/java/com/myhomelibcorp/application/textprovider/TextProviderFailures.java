package com.myhomelibcorp.application.textprovider;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Shared failure unwrapping for dictionary/translation orchestration. */
public final class TextProviderFailures {
    private TextProviderFailures() { }

    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
