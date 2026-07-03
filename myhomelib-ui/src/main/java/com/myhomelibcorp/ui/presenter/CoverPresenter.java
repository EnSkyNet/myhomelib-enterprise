package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
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
        log.info("showCover викликано для книги: {}", book != null ? book.getTitle() : "null");
        if (coverImageView == null) {
            log.warn("coverImageView == null");
            return;
        }
        clearCover();
        if (book == null) {
            log.warn("book == null");
            return;
        }

        String bookId = book.getId();
        log.info("bookId: {}, title: {}, folder: {}, fileName: {}",
                bookId, book.getTitle(), book.getFolder(), book.getFileName());
        currentLoadingBookId.set(bookId);

        backgroundExecutor.submit(() -> {
            log.info("Починаємо завантаження обкладинки для: {}", book.getTitle());
            return coverExtractor.extractCover(book);
        }).thenAccept(image -> UiExecutor.runOnUiThread(() -> {
            if (coverImageView != null && bookId.equals(currentLoadingBookId.get())) {
                if (image != null) {
                    log.info("Обкладинку завантажено, встановлюємо в ImageView");
                    coverImageView.setImage(image);
                } else {
                    log.warn("image == null для книги: {}", book.getTitle());
                }
            } else {
                log.debug("Пропускаємо встановлення – книга вже не вибрана або ImageView null");
            }
        })).exceptionally(ex -> {
            log.error("Помилка завантаження обкладинки для: {}", book.getTitle(), ex);
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