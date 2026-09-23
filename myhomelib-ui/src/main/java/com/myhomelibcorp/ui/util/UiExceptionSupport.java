package com.myhomelibcorp.ui.util;

import com.myhomelibcorp.ui.service.UiText;

import javax.net.ssl.SSLException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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

    /**
     * Returns a localized, user-safe description of a failure.
     *
     * <p>Raw exception messages are deliberately not returned here. They can contain English
     * driver/OS text, SQL fragments, URLs, local paths or other implementation details. UI code
     * should use this method and keep the original throwable only for logging/diagnostics.</p>
     */
    public static String message(Throwable error) {
        Throwable current = unwrapAsync(error);
        if (current == null) return UiText.text("common.error.unknown");

        if (isCancellation(current)) return UiText.text("common.error.cancelled");

        boolean ioFailure = false;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return UiText.text("common.error.timeout");
            }
            if (current instanceof AccessDeniedException || current instanceof SecurityException) {
                return UiText.text("common.error.access_denied");
            }
            if (current instanceof NoSuchFileException || current instanceof FileNotFoundException) {
                return UiText.text("common.error.file_not_found");
            }
            if (current instanceof UnknownHostException || current instanceof ConnectException
                    || current instanceof NoRouteToHostException || current instanceof SSLException) {
                return UiText.text("common.error.network");
            }
            if (isSimpleNamed(current, "SQLException") || isNamed(current, "org.springframework.dao.DataAccessException")) {
                return UiText.text("common.error.database");
            }
            if (current instanceof IllegalArgumentException) {
                return UiText.text("common.error.invalid_data");
            }
            if (current instanceof FileSystemException || current instanceof IOException) {
                ioFailure = true;
            }
            current = current.getCause();
        }
        if (ioFailure) return UiText.text("common.error.io");
        return UiText.text("common.error.operation_failed");
    }

    public static boolean isCancellation(Throwable error) {
        Throwable current = unwrapAsync(error);
        while (current != null) {
            if (current instanceof CancellationException || current instanceof InterruptedException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** Raw root-cause text for logs/support bundles only; never render this directly in UI. */
    public static String technicalMessage(Throwable error) {
        Throwable current = unwrapAsync(error);
        if (current == null) return "Unknown error";
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getName() : message;
    }

    private static boolean isNamed(Throwable error, String className) {
        Class<?> type = error == null ? null : error.getClass();
        while (type != null) {
            if (type.getName().equals(className)) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean isSimpleNamed(Throwable error, String simpleName) {
        Class<?> type = error == null ? null : error.getClass();
        while (type != null) {
            if (type.getSimpleName().equals(simpleName)) return true;
            type = type.getSuperclass();
        }
        return false;
    }

}
