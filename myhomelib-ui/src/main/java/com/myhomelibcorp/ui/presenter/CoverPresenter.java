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

import java.util.concurrent.atomic.AtomicReference;

@Component
@Scope("prototype")   // FIX: кожен контролер отримує власний екземпляр
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
        log.info("CoverPresenter прив'язано до ImageView: {}", coverImageView != null);
    }

    public void showCover(BookViewModel book) {
        if (coverImageView == null) {
            log.warn("showCover: coverImageView == null");
            return;
        }
        if (book == null) {
            log.warn("showCover: book == null");
            return;
        }

        String bookId = book.getId();
        log.info("showCover: завантаження обкладинки для книги: {}, id={}", book.getTitle(), bookId);

        // Якщо це та ж книга і обкладинка вже завантажена – пропускаємо
        if (bookId.equals(lastLoadedBookId) && book.getCover() != null) {
            log.info("showCover: обкладинка вже завантажена для цієї книги, пропускаємо");
            return;
        }

        // Очищаємо ImageView перед новим завантаженням
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
        lastLoadedBookId = null; // Скидаємо, щоб завантажити

        backgroundExecutor.submit(() -> {
            log.info("CoverPresenter: запит обкладинки для bookId={}", bookId);
            return coverExtractor.extractCover(dto);
        }).thenAccept(image -> UiExecutor.runOnUiThread(() -> {
            log.info("CoverPresenter: отримано обкладинку для bookId={}, image={}", bookId, image != null ? "так" : "ні");
            if (coverImageView != null && bookId.equals(currentLoadingBookId.get())) {
                if (image != null) {
                    coverImageView.setImage(image);
                    book.setCover(image);
                    lastLoadedBookId = bookId;
                    log.info("CoverPresenter: обкладинку встановлено для {}", book.getTitle());
                } else {
                    log.warn("CoverPresenter: обкладинка не знайдена для {}", book.getTitle());
                    coverImageView.setImage(null);
                }
            } else {
                log.debug("CoverPresenter: пропускаємо застарілий запит для bookId={}, поточний={}", bookId, currentLoadingBookId.get());
            }
        })).exceptionally(ex -> {
            log.error("CoverPresenter: помилка завантаження обкладинки для {}", book.getTitle(), ex);
            return null;
        });
    }

    public void clearCover() {
        currentLoadingBookId.set(null);
        lastLoadedBookId = null;
        UiExecutor.runOnUiThread(() -> {
            if (coverImageView != null) {
                coverImageView.setImage(null);
                log.debug("CoverPresenter: обкладинку очищено");
            }
        });
    }
}