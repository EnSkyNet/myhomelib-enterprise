package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Owns the asynchronous pending-catalog-update badge lifecycle for the main shell.
 * Generation and collection-id checks prevent a stale background result from being
 * applied after the active collection has changed.
 */
@Component
@RequiredArgsConstructor
public class CatalogUpdateBadgeService {
    private final CatalogUpdateService catalogUpdateService;
    private final UiBackgroundExecutor uiBackgroundExecutor;
    private final LocalizationService localizationService;

    private long generation;

    public void refresh(MenuItem updatesMenuItem, String collectionId, Supplier<String> currentCollectionId) {
        if (updatesMenuItem == null) return;
        long requestGeneration = ++generation;
        String requestedId = collectionId == null ? "" : collectionId;
        if (requestedId.isBlank()) {
            updatesMenuItem.setText(localizationService.text("ui.main.catalog_updates"));
            return;
        }
        uiBackgroundExecutor.submit(catalogUpdateService::pendingUpdateCount)
                .whenComplete((count, error) -> Platform.runLater(() -> applyResult(
                        updatesMenuItem, requestGeneration, requestedId, currentCollectionId, count, error)));
    }

    private void applyResult(MenuItem updatesMenuItem, long requestGeneration, String collectionId,
                             Supplier<String> currentCollectionId, Long count, Throwable error) {
        String currentId = currentCollectionId == null ? "" : currentCollectionId.get();
        if (requestGeneration != generation || !collectionId.equals(currentId)) return;
        if (error != null) {
            updatesMenuItem.setText(localizationService.text("ui.main.catalog_updates"));
            return;
        }
        long unread = count == null ? 0L : count;
        updatesMenuItem.setText(unread > 0
                ? localizationService.format("ui.main.catalog_updates_count", unread)
                : localizationService.text("ui.main.catalog_updates"));
    }
}
