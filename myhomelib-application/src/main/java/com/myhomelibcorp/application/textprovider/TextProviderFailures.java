package com.myhomelibcorp.application.textprovider;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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

    public static TextProviderErrorKind classify(Throwable error) {
        Throwable root = unwrap(error);
        if (root instanceof TextProviderException providerFailure) return providerFailure.kind();
        if (root instanceof TimeoutException) return TextProviderErrorKind.TIMEOUT;
        if (root instanceof CancellationException) return TextProviderErrorKind.CANCELLED;
        return TextProviderErrorKind.FAILED;
    }
}
