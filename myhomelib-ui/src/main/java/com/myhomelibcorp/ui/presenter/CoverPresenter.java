package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
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

    public void showCover(BookViewModel book) {
        if (coverImageView == null || book == null) return;
        clearCover();

        BookDto dto = new BookDto();
        dto.setId(book.getId());
        dto.setTitle(book.getTitle());
        dto.setAuthorsText(book.getAuthorsText());
        dto.setSeries(book.getSeries());
        dto.setGenresText(book.getGenresText());
        dto.setFolder(book.getFolder());
        dto.setFileName(book.getFileName());
        dto.setArchiveEntry(book.getArchiveEntry());
        dto.setCollectionRoot(book.getCollectionRoot());

        String bookId = book.getId();
        currentLoadingBookId.set(bookId);

        backgroundExecutor.submit(() -> coverExtractor.extractCover(dto))
                .thenAccept(image -> UiExecutor.runOnUiThread(() -> {
                    if (coverImageView != null && bookId.equals(currentLoadingBookId.get())) {
                        coverImageView.setImage(image);
                        if (image != null) {
                            book.setCover(image);
                        }
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