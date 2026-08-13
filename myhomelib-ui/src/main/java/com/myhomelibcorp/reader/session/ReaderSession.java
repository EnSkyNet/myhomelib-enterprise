package com.myhomelibcorp.reader.session;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.reader.model.ReaderPosition;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
@Setter
@Builder
public class ReaderSession {
    private final String sessionId;
    private final BookDto book;

    private WebView webView;
    private WebEngine webEngine;
    private ProgressBar progressBar;
    private Label progressLabel;
    private String currentHtml;
    private String lastLoadedHtml;
    private int retryCount;
    private ReaderPosition restorePosition;

    @Builder.Default
    private final AtomicBoolean contentLoaded = new AtomicBoolean(false);

    @Builder.Default
    private final AtomicBoolean isOpen = new AtomicBoolean(true);

    @Builder.Default
    private final AtomicBoolean isClosing = new AtomicBoolean(false);

    private boolean progressListenerSetup;

    public static ReaderSession create(BookDto book) {
        return ReaderSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .book(book)
                .retryCount(0)
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
        contentLoaded.set(false);
    }

    public boolean isContentLoaded() {
        return contentLoaded.get();
    }

    public void setContentLoaded(boolean value) {
        contentLoaded.set(value);
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