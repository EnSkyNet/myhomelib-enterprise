package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
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
    private volatile UiBackgroundExecutor.CancellableSubmission<CoverPayload> coverTask;
    private byte[] displayedCoverDigest;
    private CoverRequestKey displayedCoverRequest;
    private CoverRequestKey lastLoadedRequest = null;
    private boolean lastCoverAbsent;

    public void bind(ImageView coverImageView) {
        this.coverImageView = coverImageView;
        log.info("CoverPresenter прив'язано до ImageView");
    }

    public void showCover(BookViewModel book) {
        if (book == null) return;
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
        showCoverInternal(dto, book);
    }

    /**
     * Preferred path for details/workspace flows: keep the authoritative storage identity from the
     * reconciled BookDto instead of projecting it through a presentation model first. This preserves
     * libId/artifact/local-path information used by online/local archive resolution.
     */
    public void showCover(BookDto book) {
        showCoverInternal(book, null);
    }

    private void showCoverInternal(BookDto book, BookViewModel targetViewModel) {
        if (coverImageView == null || book == null) return;

        String bookId = book.getId();
        CoverRequestKey requestKey = new CoverRequestKey(
                applicationState.getCurrentLibraryCollectionId(), bookId,
                safe(book.getCollectionRoot()), safe(book.getFolder()), safe(book.getFileName()),
                safe(book.getArchiveEntry()), safe(book.getPreferredArtifactId()));
        log.debug("Завантаження обкладинки для книги: collectionId={}, id={}, title={}",
                requestKey.collectionId(), bookId, book.getTitle());

        if (requestKey.equals(lastLoadedRequest)
                && (coverImageView.getImage() != null || lastCoverAbsent)) return;
        if (requestKey.equals(currentLoadingRequest.get())) return;

        // The details pane first supplies its catalogue DTO and shortly afterwards an enriched
        // DTO for the same book. The second identity can differ without changing cover pixels.
        // Do not blank an already displayed cover while revalidating that same logical book.
        boolean sameDisplayedBook = sameLogicalBook(displayedCoverRequest, requestKey);
        if (!sameDisplayedBook) {
            coverImageView.setImage(null);
            displayedCoverDigest = null;
            displayedCoverRequest = null;
        }
        cancelCurrentTask();
        lastLoadedRequest = null;
        lastCoverAbsent = false;

        currentLoadingRequest.set(requestKey);
        long startedNanos = System.nanoTime();
        log.info("COVER_START title='{}' id={} file='{}' archiveEntry='{}'",
                book.getTitle(), bookId, book.getFileName(), book.getArchiveEntry());

        UiBackgroundExecutor.CancellableSubmission<CoverPayload> submission =
                backgroundExecutor.submitInteractiveCancellable(() -> {
                    log.debug("Виклик coverExtractor.extractCover для bookId={}", bookId);
                    byte[] bytes = coverExtractor.extractCover(book);
                    return CoverPayload.from(bytes);
                });
        coverTask = submission;
        submission.completion().thenAccept(payload -> UiExecutor.runOnUiThread(() -> {
            boolean sameRequest = requestKey.equals(currentLoadingRequest.get());
            boolean sameCollection = Objects.equals(
                    requestKey.collectionId(), applicationState.getCurrentLibraryCollectionId());
            if (coverImageView != null && sameRequest && sameCollection && coverTask == submission) {
                byte[] imageData = payload == null ? null : payload.bytes();
                if (imageData != null && imageData.length > 0) {
                    // Never replace the JavaFX texture for the exact same pixels. Avoids both a
                    // visible blink and an unnecessary second decode after rich-details enrichment.
                    if (coverImageView.getImage() != null && displayedCoverDigest != null
                            && Arrays.equals(displayedCoverDigest, payload.digest())) {
                        lastLoadedRequest = requestKey;
                        displayedCoverRequest = requestKey;
                        lastCoverAbsent = false;
                        if (targetViewModel != null) targetViewModel.setCover(coverImageView.getImage());
                        long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
                        log.info("COVER_DONE title='{}' id={} bytes={} durationMs={} reused=true",
                                book.getTitle(), bookId, imageData.length, durationMs);
                    } else {
                        Image fxImage = new Image(new ByteArrayInputStream(imageData),
                                800, 1200, true, true); // bounded decode for thumbnail and high-DPI UI
                        if (fxImage.isError() || fxImage.getWidth() <= 0 || fxImage.getHeight() <= 0) {
                            Throwable decodeFailure = fxImage.getException();
                            coverExtractor.invalidateCover(book);
                            coverImageView.setImage(null);
                            displayedCoverDigest = null;
                            displayedCoverRequest = null;
                            if (targetViewModel != null) targetViewModel.setCover(null);
                            lastLoadedRequest = null;
                            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
                            log.warn("COVER_FAIL title='{}' id={} bytes={} durationMs={} stage=decode error={}",
                                    book.getTitle(), bookId, imageData.length, durationMs,
                                    decodeFailure == null ? "unsupported-or-corrupt-image"
                                            : UiExceptionSupport.technicalMessage(decodeFailure));
                        } else {
                            coverImageView.setImage(fxImage);
                            if (targetViewModel != null) targetViewModel.setCover(fxImage);
                            displayedCoverDigest = payload.digest();
                            displayedCoverRequest = requestKey;
                            lastLoadedRequest = requestKey;
                            lastCoverAbsent = false;
                            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
                            log.info("COVER_DONE title='{}' id={} bytes={} width={} height={} durationMs={}",
                                    book.getTitle(), bookId, imageData.length, fxImage.getWidth(), fxImage.getHeight(), durationMs);
                        }
                    }
                } else {
                    log.debug("Обкладинка відсутня для {}", book.getTitle());
                    coverImageView.setImage(null);
                    displayedCoverDigest = null;
                    displayedCoverRequest = null;
                    if (targetViewModel != null) targetViewModel.setCover(null);
                    lastLoadedRequest = requestKey;
                    lastCoverAbsent = true;
                    long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
                    log.info("COVER_NONE title='{}' id={} durationMs={}", book.getTitle(), bookId, durationMs);
                }
                currentLoadingRequest.compareAndSet(requestKey, null);
                if (coverTask == submission) coverTask = null;
            } else {
                log.debug("Пропускаємо застарілий запит обкладинки: requested={}, current={}, activeCollectionId={}",
                        requestKey, currentLoadingRequest.get(), applicationState.getCurrentLibraryCollectionId());
            }
        })).exceptionally(ex -> {
            Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null
                    ? ex.getCause() : ex;
            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            if (cause instanceof java.util.concurrent.CancellationException) {
                log.debug("COVER_CANCEL title='{}' id={} durationMs={}", book.getTitle(), bookId, durationMs);
            } else {
                log.warn("COVER_FAIL title='{}' id={} durationMs={} error={}",
                        book.getTitle(), bookId, durationMs, UiExceptionSupport.technicalMessage(cause));
            }
            UiExecutor.runOnUiThread(() -> {
                if (coverTask == submission) {
                    coverTask = null;
                    currentLoadingRequest.compareAndSet(requestKey, null);
                }
            });
            return null;
        });
    }

    public void clearCover() {
        cancelCurrentTask();
        currentLoadingRequest.set(null);
        lastLoadedRequest = null;
        lastCoverAbsent = false;
        displayedCoverDigest = null;
        displayedCoverRequest = null;
        // A queued clear must not erase an image loaded after the clear was requested.
        UiExecutor.runOnUiThread(() -> {
            if (coverImageView != null && currentLoadingRequest.get() == null
                    && lastLoadedRequest == null) {
                coverImageView.setImage(null);
            }
        });
    }

    private void cancelCurrentTask() {
        UiBackgroundExecutor.CancellableSubmission<CoverPayload> current = coverTask;
        coverTask = null;
        if (current != null) current.cancel();
    }

    private static boolean sameLogicalBook(CoverRequestKey earlier, CoverRequestKey next) {
        return earlier != null && next != null
                && Objects.equals(earlier.collectionId(), next.collectionId())
                && Objects.equals(earlier.bookId(), next.bookId());
    }

    private record CoverPayload(byte[] bytes, byte[] digest) {
        static CoverPayload from(byte[] data) throws NoSuchAlgorithmException {
            return data == null || data.length == 0 ? new CoverPayload(null, null)
                    : new CoverPayload(data, MessageDigest.getInstance("SHA-256").digest(data));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record CoverRequestKey(String collectionId, String bookId, String collectionRoot,
                                   String folder, String fileName, String archiveEntry,
                                   String preferredArtifactId) {
    }
}
