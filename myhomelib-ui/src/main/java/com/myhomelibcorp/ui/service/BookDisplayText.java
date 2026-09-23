package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;

/** Language-aware formatting for book values that must not be localized in application DTOs. */
public final class BookDisplayText {
    private BookDisplayText() {}

    public static String fileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return UiText.format("ui.book.size.bytes", bytes);
        if (bytes < 1024L * 1024L) return UiText.format("ui.book.size.kb", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return UiText.format("ui.book.size.mb", bytes / (1024.0 * 1024.0));
        return UiText.format("ui.book.size.gb", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String localStatus(boolean local, boolean missing) {
        if (local) return UiText.text("ui.book.status.local");
        return UiText.text(missing ? "ui.book.status.missing" : "ui.book.status.remote");
    }

    public static String localStatus(BookDto book) {
        if (book == null) return "";
        return localStatus(book.isLocal(), book.getMissingSince() != null);
    }
}
