package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.audio.AudioPosition;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Owns asynchronous bookmark persistence for Reader workspaces.
 *
 * <p>Every completion is revalidated against the originating book before it reaches UI code.
 * Collection lifecycle conflicts are expected stale actions during switch/close and are therefore
 * dropped instead of being surfaced as persistence failures.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderBookmarkCoordinator {
    private final NewReaderPersistenceService persistenceService;
    private final UiBackgroundExecutor backgroundExecutor;

    public void saveText(
            Context context,
            ReaderPosition position,
            long totalLength,
            String title,
            Consumer<ReaderBookmarkItem> onSuccess,
            Consumer<Throwable> onFailure) {
        if (!usable(context) || position == null) return;
        submit(context,
                () -> persistenceService.saveBookmarkItem(
                        context.collectionId(), context.bookId(), position, totalLength, title, ""),
                "save", onSuccess, onFailure);
    }

    public void saveAudio(
            Context context,
            AudioPosition position,
            double percent,
            String title,
            Consumer<ReaderBookmarkItem> onSuccess,
            Consumer<Throwable> onFailure) {
        if (!usable(context) || position == null) return;
        submit(context,
                () -> persistenceService.saveAudioBookmarkItem(
                        context.collectionId(), context.bookId(), position, percent, title, ""),
                "save audio", onSuccess, onFailure);
    }

    public void load(
            Context context,
            Consumer<List<ReaderBookmarkItem>> onSuccess,
            Consumer<Throwable> onFailure) {
        if (!usable(context)) return;
        submit(context,
                () -> persistenceService.loadBookmarkItems(context.collectionId(), context.bookId()),
                "load", onSuccess, onFailure);
    }

    public void delete(
            Context context,
            String bookmarkId,
            Runnable onSuccess,
            Consumer<Throwable> onFailure) {
        if (!usable(context) || bookmarkId == null || bookmarkId.isBlank()) return;
        submit(context, () -> {
            persistenceService.deleteBookmark(context.collectionId(), context.bookId(), bookmarkId);
            return Boolean.TRUE;
        }, "delete", ignored -> run(onSuccess), onFailure);
    }

    public void deleteAndReload(
            Context context,
            String bookmarkId,
            Consumer<List<ReaderBookmarkItem>> onSuccess,
            Consumer<Throwable> onFailure) {
        if (!usable(context) || bookmarkId == null || bookmarkId.isBlank()) return;
        submit(context, () -> {
            persistenceService.deleteBookmark(context.collectionId(), context.bookId(), bookmarkId);
            return persistenceService.loadBookmarkItems(context.collectionId(), context.bookId());
        }, "delete and reload", onSuccess, onFailure);
    }

    private <T> void submit(
            Context context,
            Callable<T> task,
            String action,
            Consumer<T> onSuccess,
            Consumer<Throwable> onFailure) {
        Objects.requireNonNull(task, "task");
        backgroundExecutor.submitCollectionScoped(LibraryOperationType.READER_STATE, task)
                .thenAccept(result -> deliver(context, () -> accept(onSuccess, result)))
                .exceptionally(error -> {
                    Throwable root = UiExceptionSupport.unwrapAsync(error);
                    if (root instanceof LibraryOperationConflictException) {
                        log.debug("Bookmark {} skipped because library lifecycle changed: {}", action,
                                UiExceptionSupport.technicalMessage(root));
                        return null;
                    }
                    log.error("Reader bookmark {} failed for book {}", action, context.bookId(), root);
                    deliver(context, () -> accept(onFailure, root));
                    return null;
                });
    }

    private static void deliver(Context context, Runnable action) {
        if (!context.current()) return;
        UiExecutor.runOnUiThread(() -> {
            if (context.current()) action.run();
        });
    }

    private static <T> void accept(Consumer<T> consumer, T value) {
        if (consumer != null) consumer.accept(value);
    }

    private static void run(Runnable runnable) {
        if (runnable != null) runnable.run();
    }

    private static boolean usable(Context context) {
        return context != null
                && !context.collectionId().isBlank()
                && !context.bookId().isBlank()
                && context.current();
    }

    public record Context(String collectionId, String bookId, BooleanSupplier stillCurrent) {
        public Context {
            collectionId = collectionId == null ? "" : collectionId.trim();
            bookId = bookId == null ? "" : bookId.trim();
        }

        public boolean current() {
            return stillCurrent != null && stillCurrent.getAsBoolean();
        }
    }
}
