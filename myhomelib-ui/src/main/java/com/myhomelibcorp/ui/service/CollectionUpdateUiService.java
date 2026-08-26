package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionFromNetworkUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
public class CollectionUpdateUiService {
    private static final String DEFAULT_FLIBUSTA_INPX_SERVER = "https://alex80.github.io/mhl/download/inpx/";

    private final UpdateCollectionFromNetworkUseCase useCase;
    private final ApplicationSettingsPort settings;
    private final ApplicationState state;
    private final DialogService dialogs;
    private final UiBackgroundExecutor executor;
    private volatile AtomicBoolean active;

    public void updateFromNetwork(Window owner, Runnable onDone) {
        Collection collection = state.getCurrentLibraryCollection();
        if (collection == null) {
            dialogs.showWarning("Колекція", "Спочатку виберіть колекцію.");
            return;
        }

        String sourceKey = "collection." + collection.getId() + ".inpxUrl";
        String versionKey = "collection." + collection.getId() + ".catalogVersion";
        String current = settings.get(sourceKey, "");
        if (current.isBlank() && collection.getUrl() != null) {
            String collectionUrl = collection.getUrl().trim();
            String lower = collectionUrl.toLowerCase();
            if (lower.endsWith(".inpx") || lower.endsWith(".zip") || lower.contains("alex80.github.io/mhl")) {
                current = collectionUrl;
            }
        }
        if (current.isBlank() && CollectionType.fromCode(collection.getType()) == CollectionType.REMOTE) {
            current = DEFAULT_FLIBUSTA_INPX_SERVER;
        }

        Optional<String> entered = dialogs.showTextInput(
                "Оновлення колекції",
                "Сервер INPX або прямий URL каталогу",
                "INPX server / URL:",
                current);
        if (entered.isEmpty() || entered.get().isBlank()) return;

        String source = entered.get().trim();
        String previous = settings.get(sourceKey, "");
        if (!previous.isBlank() && !previous.equals(source)) {
            // A version number belongs to a concrete server/catalog stream.
            settings.remove(versionKey);
        }
        settings.put(sourceKey, source);

        AtomicBoolean flag = new AtomicBoolean(false);
        active = flag;
        state.getStatusBar().setProgressVisible(true);
        state.getStatusBar().setProgress(0.0);
        state.getStatusBar().setStatusText("Перевірка версії та завантаження каталогу…");

        executor.submit(() -> useCase.execute(
                        collection,
                        source,
                        flag,
                        p -> Platform.runLater(() -> state.getStatusBar().setProgress(p))))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (active == flag) active = null;
                    state.getStatusBar().setProgressVisible(false);
                    if (error != null) {
                        dialogs.showError("Оновлення колекції", unwrap(error).getMessage());
                        return;
                    }
                    if (result.imported() == 0 && result.errors() == 0) {
                        state.getStatusBar().setStatusText("Каталог уже актуальний");
                        dialogs.showInfo("Оновлення", "Новіша версія каталогу на сервері відсутня.");
                    } else {
                        state.getStatusBar().setStatusText("Колекцію оновлено");
                        dialogs.showInfo("Оновлення", "Імпортовано/оновлено: " + result.imported());
                        if (onDone != null) onDone.run();
                    }
                }));
    }

    public boolean cancel() {
        AtomicBoolean flag = active;
        if (flag == null) return false;
        flag.set(true);
        state.getStatusBar().setStatusText("Скасування оновлення…");
        return true;
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
}
