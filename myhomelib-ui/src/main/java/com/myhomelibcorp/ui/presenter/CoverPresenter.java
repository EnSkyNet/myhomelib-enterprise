package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.CoverExtractor;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverPresenter {

    private final CoverExtractor coverExtractor;
    private final BackgroundExecutor backgroundExecutor;

    private ImageView coverImageView;
    private final AtomicReference<String> currentLoadingBookId = new AtomicReference<>();

    public void bind(ImageView coverImageView) {
        this.coverImageView = coverImageView;
    }

    public void showCover(BookDto book) {
        if (coverImageView == null) return;
        clearCover();
        if (book == null) return;

        String bookId = book.getId();
        currentLoadingBookId.set(bookId);

        backgroundExecutor.submit(() -> coverExtractor.extractCover(book))
                .thenAccept(image -> UiExecutor.runOnUiThread(() -> {
                    // Перевіряємо, чи книга досі вибрана
                    if (coverImageView != null && bookId.equals(currentLoadingBookId.get())) {
                        coverImageView.setImage(image);
                    }
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load cover for {}", book.getTitle(), ex);
                    return null;
                });
    }

    public void clearCover() {
        currentLoadingBookId.set(null);
        UiExecutor.runOnUiThread(() -> {
            if (coverImageView != null) coverImageView.setImage(null);
        });
    }
}