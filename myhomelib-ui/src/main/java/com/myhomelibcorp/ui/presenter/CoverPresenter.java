package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
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
    private final ApplicationState applicationState;

    private ImageView coverImageView;
    private final AtomicReference<CoverRequestKey> currentLoadingRequest = new AtomicReference<>();
    private CoverRequestKey lastLoadedRequest = null;

    public void bind(ImageView coverImageView) {
        this.coverImageView = coverImageView;
        log.info("CoverPresenter прив'язано до ImageView");
    }

    public void showCover(BookViewModel book) {
        if (coverImageView == null || book == null) {
            return;
        }

        String bookId = book.getId();
        CoverRequestKey requestKey = new CoverRequestKey(applicationState.getCurrentLibraryCollectionId(), bookId);
        log.debug("Завантаження обкладинки для книги: collectionId={}, id={}, title={}",
                requestKey.collectionId(), bookId, book.getTitle());

        // Не змішуємо результати однакових bookId з різних колекцій.
        if (requestKey.equals(lastLoadedRequest) && book.getCover() != null) {
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

        currentLoadingRequest.set(requestKey);
        lastLoadedRequest = null;

        backgroundExecutor.submitCollectionScoped(LibraryOperationType.QUERY, () -> {
            log.debug("Виклик coverExtractor.extractCover для bookId={}", bookId);
            return coverExtractor.extractCover(dto);
        }).thenAccept(imageData -> UiExecutor.runOnUiThread(() -> {
            boolean sameRequest = requestKey.equals(currentLoadingRequest.get());
            boolean sameCollection = java.util.Objects.equals(
                    requestKey.collectionId(), applicationState.getCurrentLibraryCollectionId());
            if (coverImageView != null && sameRequest && sameCollection) {
                if (imageData != null && imageData.length > 0) {
                    Image fxImage = new Image(new ByteArrayInputStream(imageData));
                    coverImageView.setImage(fxImage);
                    book.setCover(fxImage);
                    lastLoadedRequest = requestKey;
                    log.debug("Обкладинку встановлено для {}", book.getTitle());
                } else {
                    log.debug("Обкладинка відсутня для {}", book.getTitle());
                    coverImageView.setImage(null);
                }
            } else {
                log.debug("Пропускаємо застарілий запит обкладинки: requested={}, current={}, activeCollectionId={}",
                        requestKey, currentLoadingRequest.get(), applicationState.getCurrentLibraryCollectionId());
            }
        })).exceptionally(ex -> {
            log.error("Помилка завантаження обкладинки для {}", book.getTitle(), ex);
            return null;
        });
    }

    public void clearCover() {
        currentLoadingRequest.set(null);
        lastLoadedRequest = null;
        UiExecutor.runOnUiThread(() -> {
            if (coverImageView != null) {
                coverImageView.setImage(null);
            }
        });
    }

    private record CoverRequestKey(String collectionId, String bookId) {
    }
}