package com.myhomelibcorp.reader.session;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderPosition;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReaderSession {
    private String sessionId;
    private BookDto book;

    private WebView webView;
    private WebEngine webEngine;
    private ProgressBar progressBar;
    private Label progressLabel;

    private ReaderPosition restorePosition;
    private double progressPercent;

    @Builder.Default
    private double zoom = 1.0;

    @Builder.Default
    private List<Chapter> chapters = new ArrayList<>();

    @Builder.Default
    private final AtomicBoolean isOpen = new AtomicBoolean(true);
    @Builder.Default
    private final AtomicBoolean isClosing = new AtomicBoolean(false);

    public static ReaderSession create(BookDto book) {
        return ReaderSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .book(book)
                .chapters(new ArrayList<>())
                .progressPercent(0)
                .zoom(1.0)
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
}