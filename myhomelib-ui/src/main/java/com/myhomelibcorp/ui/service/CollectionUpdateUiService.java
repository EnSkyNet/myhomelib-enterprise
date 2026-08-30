package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionFromNetworkUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
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
        CatalogUpdateProgressDialog progressDialog = new CatalogUpdateProgressDialog(owner);
        progressDialog.setOnCancel(() -> {
            flag.set(true);
            state.getStatusBar().setStatusText("Скасування оновлення…");
        });
        progressDialog.show();
        state.getStatusBar().setProgressVisible(true);
        state.getStatusBar().setProgress(0.0);
        state.getStatusBar().setStatusText("Перевірка версії та завантаження каталогу…");

        executor.submit(() -> useCase.execute(
                        collection,
                        source,
                        flag,
                        p -> Platform.runLater(() -> state.getStatusBar().setProgress(p)),
                        progressDialog::update))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (active == flag) active = null;
                    state.getStatusBar().setProgressVisible(false);
                    progressDialog.close();
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        if (flag.get()) {
                            state.getStatusBar().setStatusText("Оновлення скасовано");
                            return;
                        }
                        dialogs.showError("Оновлення колекції", cause.getMessage());
                        return;
                    }
                    if (result.imported() == 0 && result.errors() == 0) {
                        state.getStatusBar().setStatusText("Каталог уже актуальний");
                        dialogs.showInfo("Оновлення", "Новіша версія каталогу на сервері відсутня.");
                    } else {
                        state.getStatusBar().setStatusText("Колекцію оновлено");
                        var changes = result.changes();
                        long processed = result.imported() + result.skipped() + result.duplicates() + result.errors();
                        String issueSummary = issueCodeSummary(result);
                        dialogs.showInfo("Оновлення",
                                "Оброблено: " + processed
                                        + "\nІмпортовано: " + result.imported()
                                        + "\nДодано: " + changes.inserted().size()
                                        + "\nОновлено: " + changes.updated().size()
                                        + "\nВидалено: " + changes.deleted().size()
                                        + "\nПропущено: " + result.skipped()
                                        + "\nДублікати: " + result.duplicates()
                                        + "\nПопередження: " + result.issues().size()
                                        + "\nПомилки: " + result.errors()
                                        + "\nТривалість: " + formatDuration(result.durationMs())
                                        + issueSummary);
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

    private static String issueCodeSummary(com.myhomelibcorp.application.imports.statistics.ImportResult result) {
        if (result == null || result.issues() == null || result.issues().isEmpty()) return "";
        java.util.Map<String, Long> counts = result.issues().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        issue -> issue.code() == null || issue.code().isBlank() ? "UNCLASSIFIED" : issue.code(),
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
        StringBuilder out = new StringBuilder("\n\nКоди проблем:");
        int shown = 0;
        for (var entry : counts.entrySet()) {
            if (shown++ >= 8) {
                out.append("\n… ще ").append(counts.size() - 8).append(" груп");
                break;
            }
            out.append("\n").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return out.toString();
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long remMillis = Math.max(0L, millis) % 1000L;
        if (minutes > 0) return minutes + " хв " + seconds + " с";
        if (totalSeconds > 0) return totalSeconds + "." + String.format(java.util.Locale.ROOT, "%03d", remMillis) + " с";
        return Math.max(0L, millis) + " мс";
    }

}
