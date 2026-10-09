package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.content.search.ContentIndexUnavailableException;
import com.myhomelibcorp.application.content.search.ContentSearchResultPage;
import com.myhomelibcorp.application.content.search.ContentSearchService;
import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns the cancellable asynchronous lifecycle of collection-scoped full-content search. */
@Slf4j
final class ContentSearchCoordinator {

    private final UiBackgroundExecutor executor;
    private final ContentSearchService contentSearchService;
    private final LocalizationService i18n;
    private final int resultLimit;
    private volatile Future<?> task;

    ContentSearchCoordinator(UiBackgroundExecutor executor,
                             ContentSearchService contentSearchService,
                             LocalizationService i18n,
                             int resultLimit) {
        this.executor = executor;
        this.contentSearchService = contentSearchService;
        this.i18n = i18n;
        this.resultLimit = resultLimit;
    }

    void search(String collectionId,
                String query,
                boolean reportContentStatus,
                BooleanSupplier contextStillCurrent,
                Consumer<ContentSearchResultPage> publishResults,
                Consumer<String> publishStatus) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank() || collectionId == null || collectionId.isBlank()) {
            publishResults.accept(ContentSearchResultPage.empty(resultLimit));
            return;
        }

        try {
            task = executor.submitCollectionScopedCancellable(LibraryOperationType.QUERY, () -> {
                try {
                    ContentSearchResultPage page = contentSearchService.search(collectionId, normalized, 0, resultLimit);
                    if (Thread.currentThread().isInterrupted()) return null;
                    UiExecutor.runOnUiThread(() -> {
                        if (!contextStillCurrent.getAsBoolean()) return;
                        publishResults.accept(page);
                        if (reportContentStatus) {
                            publishStatus.accept(page.total() == 0
                                    ? i18n.text("ui.search.status.nothing_found")
                                    : i18n.format("ui.search.contents.status", page.items().size(), page.total()));
                        }
                    });
                } catch (CancellationException cancelled) {
                    // A newer query owns the workspace now.
                } catch (RuntimeException failure) {
                    if (!Thread.currentThread().isInterrupted()) {
                        log.error("Content search failed", failure);
                        UiExecutor.runOnUiThread(() -> {
                            if (!contextStillCurrent.getAsBoolean()) return;
                            if (failure instanceof ContentIndexUnavailableException) {
                                publishStatus.accept(i18n.text("ui.search.contents.index_not_ready"));
                            } else {
                                publishStatus.accept(i18n.format("ui.search.status.error", UiExceptionSupport.message(failure)));
                            }
                        });
                    }
                }
                return null;
            });
        } catch (LibraryOperationConflictException | RejectedExecutionException busyOrRejected) {
            log.debug("Content search was not scheduled: {}", busyOrRejected.toString());
            UiExecutor.runOnUiThread(() -> {
                if (contextStillCurrent.getAsBoolean()) {
                    publishStatus.accept(i18n.format("ui.search.status.error", UiExceptionSupport.message(busyOrRejected)));
                }
            });
        }
    }

    void cancel() {
        Future<?> currentTask = task;
        task = null;
        if (currentTask != null && !currentTask.isDone()) currentTask.cancel(true);
    }
}
