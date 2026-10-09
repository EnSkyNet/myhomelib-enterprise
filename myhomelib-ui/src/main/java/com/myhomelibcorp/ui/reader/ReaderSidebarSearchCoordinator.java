package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.service.ReaderSearchService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Owns the asynchronous lifecycle of the reader sidebar full-text search.
 *
 * <p>The controller remains responsible for JavaFX controls and reader navigation; this coordinator
 * only owns request generation, task cancellation, queue saturation handling and guarded publication
 * of search outcomes.</p>
 */
final class ReaderSidebarSearchCoordinator {

    private final UiBackgroundExecutor uiBackgroundExecutor;
    private final LocalizationService i18n;
    private final AtomicLong generation = new AtomicLong();
    private volatile Future<?> task;

    ReaderSidebarSearchCoordinator(UiBackgroundExecutor uiBackgroundExecutor, LocalizationService i18n) {
        this.uiBackgroundExecutor = uiBackgroundExecutor;
        this.i18n = i18n;
    }

    void search(ReaderDocument document,
                String query,
                BooleanSupplier contextStillCurrent,
                Consumer<List<ReaderSearchService.SearchResult>> publishResults,
                Consumer<String> publishStatus) {
        if (document == null || query == null || query.isBlank()) {
            cancel();
            publishStatus.accept(i18n.text("ui.reader.search.enter_text"));
            publishResults.accept(List.of());
            return;
        }

        cancel();
        long requestGeneration = generation.incrementAndGet();
        publishStatus.accept(i18n.text("ui.reader.search.searching"));
        try {
            task = uiBackgroundExecutor.submitCancellable(() -> {
                try {
                    List<ReaderSearchService.SearchResult> results =
                            new ReaderSearchService().search(document, query.strip());
                    Platform.runLater(() -> publish(requestGeneration, contextStillCurrent,
                            publishResults, publishStatus, results, null));
                } catch (Throwable error) {
                    Platform.runLater(() -> publish(requestGeneration, contextStillCurrent,
                            publishResults, publishStatus, List.of(), error));
                }
                return null;
            });
        } catch (RejectedExecutionException busy) {
            publishStatus.accept(i18n.text("ui.reader.search.queue_busy"));
        }
    }

    void cancel() {
        generation.incrementAndGet();
        Future<?> currentTask = task;
        task = null;
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
    }

    private void publish(long requestGeneration,
                         BooleanSupplier contextStillCurrent,
                         Consumer<List<ReaderSearchService.SearchResult>> publishResults,
                         Consumer<String> publishStatus,
                         List<ReaderSearchService.SearchResult> results,
                         Throwable error) {
        if (requestGeneration != generation.get() || !contextStillCurrent.getAsBoolean()) {
            return;
        }
        if (error != null) {
            if (error instanceof CancellationException) {
                return;
            }
            publishStatus.accept(UiExceptionSupport.message(error));
            publishResults.accept(List.of());
            return;
        }

        publishResults.accept(results);
        publishStatus.accept(results.isEmpty()
                ? i18n.text("ui.reader.search.nothing_found")
                : i18n.format("ui.reader.search.matches_found", results.size()));
    }
}
