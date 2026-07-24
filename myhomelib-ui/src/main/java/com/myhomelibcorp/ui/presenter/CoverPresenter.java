package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class CoverPresenter {

    private final CoverExtractor coverExtractor;
    private final UiBackgroundExecutor backgroundExecutor;

    private ImageView coverImageView;
    private final AtomicReference<String> currentLoadingBookId = new AtomicReference<>();
    private String lastLoadedBookId = null;

    public void bind(ImageView coverImageView) {
        this.coverImageView = coverImageView;
        log.info("CoverPresenter прив'язано до ImageView");
    }

    public void showCover(BookViewModel book) {
        if (coverImageView == null || book == null) {
            return;
        }

        String bookId = book.getId();
        log.debug("Завантаження обкладинки для книги: id={}, title={}", bookId, book.getTitle());

        // Якщо це та ж книга і обкладинка вже завантажена — пропускаємо
        if (bookId.equals(lastLoadedBookId) && book.getCover() != null) {
            return;
        }

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
        dto.setProgress(book.getProgress());

        currentLoadingBookId.set(bookId);
        lastLoadedBookId = null;

        backgroundExecutor.submit(() -> {
            log.debug("Виклик coverExtractor.extractCover для bookId={}", bookId);
            return coverExtractor.extractCover(dto);
        }).thenAccept(imageData -> UiExecutor.runOnUiThread(() -> {
            if (coverImageView != null && bookId.equals(currentLoadingBookId.get())) {
                if (imageData != null && imageData.length > 0) {
                    Image fxImage = new Image(new ByteArrayInputStream(imageData));
                    coverImageView.setImage(fxImage);
                    book.setCover(fxImage);
                    lastLoadedBookId = bookId;
                    log.debug("Обкладинку встановлено для {}", book.getTitle());
                } else {
                    log.warn("Обкладинка не знайдена для {}", book.getTitle());
                    coverImageView.setImage(null);
                }
            } else {
                log.debug("Пропускаємо застарілий запит для bookId={}, поточний={}", bookId, currentLoadingBookId.get());
            }
        })).exceptionally(ex -> {
            log.error("Помилка завантаження обкладинки для {}", book.getTitle(), ex);
            return null;
        });
    }

    public void clearCover() {
        currentLoadingBookId.set(null);
        lastLoadedBookId = null;
        UiExecutor.runOnUiThread(() -> {
            if (coverImageView != null) {
                coverImageView.setImage(null);
            }
        });
    }
}