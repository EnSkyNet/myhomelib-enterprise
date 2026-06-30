package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.CoverExtractor;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverPresenter {

    private final CoverExtractor coverExtractor;
    private final BackgroundExecutor backgroundExecutor;

    private ImageView coverImageView;

    public void bind(ImageView coverImageView) {
        this.coverImageView = coverImageView;
    }

    public void showCover(BookDto book) {
        if (coverImageView == null) return;
        clearCover();
        if (book == null) return;

        backgroundExecutor.submit(() -> coverExtractor.extractCover(book))
                .thenAccept(image -> UiExecutor.runOnUiThread(() -> {
                    if (coverImageView != null) {
                        coverImageView.setImage(image);
                    }
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load cover for {}", book.getTitle(), ex);
                    return null;
                });
    }

    public void clearCover() {
        UiExecutor.runOnUiThread(() -> {
            if (coverImageView != null) coverImageView.setImage(null);
        });
    }
}