package com.myhomelibcorp.ui.event;

import com.myhomelibcorp.application.event.CollectionSourceUpdateAvailableEvent;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Surfaces background source changes globally, even when Collection Workspace is closed. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionSourceUpdateEventHandler {
    private final ApplicationState appState;

    @EventListener
    public void onCollectionSourceUpdate(CollectionSourceUpdateAvailableEvent event) {
        log.info("Collection source update available: collection={}, source={}",
                event.collectionId(), event.sourceFile());
        UiExecutor.runOnUiThread(() -> {
            var current = appState.getCurrentLibraryCollection();
            String collection = current != null && event.collectionId().equals(current.getId())
                    ? current.getName() : event.collectionId();
            appState.getStatusBar().setStatusText(
                    "Доступне оновлення колекції «" + collection + "»: " + event.sourceFile().getFileName());
        });
    }
}
