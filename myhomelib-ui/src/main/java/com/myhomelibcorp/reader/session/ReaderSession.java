package com.myhomelibcorp.reader.session;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderPosition;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сесія Reader для однієї книги.
 * Містить тільки необхідні дані для роботи.
 */
@Getter
@Setter
@Builder
public class ReaderSession {

    private final String sessionId;
    private final BookDto book;

    // ===== UI компоненти =====
    private WebView webView;
    private WebEngine webEngine;
    private ProgressBar progressBar;
    private Label progressLabel;

    // ===== Позиція для відновлення =====
    private ReaderPosition restorePosition;

    // ===== TOC (зберігається під час парсингу) =====
    @Builder.Default
    private List<Chapter> chapters = new ArrayList<>();

    // ===== Стан =====
    @Builder.Default
    private final AtomicBoolean isOpen = new AtomicBoolean(true);

    @Builder.Default
    private final AtomicBoolean isClosing = new AtomicBoolean(false);

    public static ReaderSession create(BookDto book) {
        return ReaderSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .book(book)
                .chapters(new ArrayList<>())
                .build();
    }

    public String getBookId() {
        return book != null ? book.getId() : null;
    }

    public boolean isActive() {
        return isOpen.get() && !isClosing.get();
    }

    public void markClosing() {
        isClosing.set(true);
    }

    public void markClosed() {
        isOpen.set(false);
        isClosing.set(false);
    }

    public boolean isOpen() {
        return isOpen.get();
    }

    public void setOpen(boolean value) {
        isOpen.set(value);
    }

    public boolean isClosing() {
        return isClosing.get();
    }

    public void setClosing(boolean value) {
        isClosing.set(value);
    }
}