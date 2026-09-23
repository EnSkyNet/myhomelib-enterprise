package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionFromNetworkUseCase;
import com.myhomelibcorp.application.usecase.collection.CatalogUpdateFailureException;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.LibraryOperationUiText;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class CollectionUpdateUiService {
    private static final String DEFAULT_FLIBUSTA_INPX_SERVER = "https://alex80.github.io/mhl/download/inpx/";

    private final UpdateCollectionFromNetworkUseCase useCase;
    private final ApplicationSettingsPort settings;
    private final ApplicationState state;
    private final DialogService dialogs;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final LibraryOperationCoordinator libraryOperations;
    private volatile AtomicBoolean active;
    private final AtomicBoolean startupCheckStarted = new AtomicBoolean(false);

    public void updateFromNetwork(Window owner, Runnable onDone) {
        Collection collection = state.getCurrentLibraryCollection();
        if (collection == null) {
            dialogs.showWarning(UiText.text("ui.fxml.collection_workspace.collection"), UiText.text("ui.java.collection_update_ui_service.6686ed3c16"));
            return;
        }

        LibraryOperationType blockingOperation = libraryOperations.activeOperation();
        if (blockingOperation != null) {
            dialogs.showWarning(UiText.text("ui.import.action.collection_update"),
                    LibraryOperationUiText.blockingMessage(UiText.text("ui.import.action.collection_update"), blockingOperation));
            return;
        }

        String sourceKey = "collection." + collection.getId() + ".inpxUrl";
        String versionKey = "collection." + collection.getId() + ".catalogVersion";
        String current = settings.get(sourceKey, "");
        if (current.isBlank() && collection.getUrl() != null) {
            String collectionUrl = collection.getUrl().trim();
            String lower = collectionUrl.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".inpx") || lower.endsWith(".zip") || lower.contains("alex80.github.io/mhl")) {
                current = collectionUrl;
            }
        }
        if (current.isBlank() && CollectionType.fromCode(collection.getType()) == CollectionType.REMOTE) {
            current = DEFAULT_FLIBUSTA_INPX_SERVER;
        }

        Optional<String> entered = dialogs.showTextInput(
                UiText.text("ui.import.action.collection_update"),
                UiText.text("ui.java.collection_update_ui_service.cdf66f1437"),
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

        startManualUpdate(owner, onDone, collection, source);
    }

    private void startManualUpdate(Window owner, Runnable onDone, Collection collection, String source) {
        AtomicBoolean flag = new AtomicBoolean(false);
        if (active != null) {
            dialogs.showWarning(UiText.text("ui.import.action.collection_update"), UiText.text("ui.java.collection_update_ui_service.611ffc7e8f"));
            return;
        }
        active = flag;
        CatalogUpdateProgressDialog progressDialog = new CatalogUpdateProgressDialog(owner);
        progressDialog.setOnCancel(() -> {
            flag.set(true);
            state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.2cee850aef"));
        });
        progressDialog.show();
        state.getStatusBar().setProgressVisible(true);
        state.getStatusBar().setProgress(0.0);
        state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.fb9cf0f86e"));

        executor.submit(() -> useCase.execute(
                        collection,
                        source,
                        flag,
                        p -> Platform.runLater(() -> {
                            if (isActiveCollection(collection.getId())) state.getStatusBar().setProgress(p);
                        }),
                        progress -> {
                            operationCenter.accept(UiText.text("ui.fxml.updates_workspace.catalog_updates"), collection.getId(), progress);
                            progressDialog.update(progress);
                        }))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (active == flag) active = null;
                    progressDialog.close();
                    if (!isActiveCollection(collection.getId())) return;
                    state.getStatusBar().setProgressVisible(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        if (flag.get()) {
                            state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.6fb2759d5e"));
                            return;
                        }
                        if (cause instanceof LibraryOperationConflictException conflict) {
                            state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.d7b3f75207"));
                            dialogs.showWarning(UiText.text("ui.import.action.collection_update"),
                                    LibraryOperationUiText.blockingMessage(UiText.text("ui.import.action.collection_update"), conflict.activeOperation()));
                            return;
                        }
                        if (cause instanceof CatalogUpdateFailureException failure) {
                            state.getStatusBar().setStatusText(updateFailureStatus(failure));
                            boolean safeToRetry = !failure.mutationMayHaveCommitted() || failure.rollbackSucceeded();
                            if (safeToRetry) {
                                boolean retry = dialogs.showErrorWithRetry(
                                        UiText.text("ui.import.action.collection_update"),
                                        UiText.text("ui.java.collection_update_ui_service.6b98cf4cab"),
                                        updateFailureDetails(failure));
                                if (retry && isActiveCollection(collection.getId())) {
                                    Platform.runLater(() -> startManualUpdate(owner, onDone, collection, source));
                                }
                            } else {
                                dialogs.showError(UiText.text("ui.import.action.collection_update"),
                                        UiText.text("ui.java.collection_update_ui_service.5dc92c032d"),
                                        updateFailureDetails(failure));
                            }
                        } else {
                            state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.5b79655853"));
                            dialogs.showError(UiText.text("ui.import.action.collection_update"), UiExceptionSupport.message(cause));
                        }
                        return;
                    }
                    if (result.imported() == 0 && result.errors() == 0) {
                        state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.8c30f24853"));
                        dialogs.showInfo(UiText.text("ui.fxml.updates_workspace.updates"), UiText.text("ui.java.collection_update_ui_service.5a945c5bde"));
                    } else {
                        state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.25b1cf25dd"));
                        var changes = result.changes();
                        long processed = result.imported() + result.skipped() + result.duplicates() + result.errors();
                        String issueSummary = issueCodeSummary(result);
                        dialogs.showInfo(UiText.text("ui.fxml.updates_workspace.updates"),
                                UiText.text("ui.java.collection_update_ui_service.b077de9d62") + processed
                                        + UiText.text("ui.java.collection_update_ui_service.ec96be6335") + result.imported()
                                        + UiText.text("ui.java.collection_update_ui_service.e849588cbb") + changes.insertedCount()
                                        + UiText.text("ui.java.collection_update_ui_service.362500a9c6") + changes.updatedCount()
                                        + UiText.text("ui.java.collection_update_ui_service.e8b7572cd7") + result.explicitlyDeleted()
                                        + UiText.text("ui.java.collection_update_ui_service.75aa861b30") + changes.deletedCount()
                                        + UiText.text("ui.java.collection_update_ui_service.b22c2b82e3") + result.withoutAuthor()
                                        + UiText.text("ui.java.collection_update_ui_service.00d91c267b") + result.withoutGenre()
                                        + UiText.text("ui.java.collection_update_ui_service.465bccc5fe") + result.skipped()
                                        + UiText.text("ui.java.collection_update_ui_service.2f62f662a1") + result.duplicates()
                                        + UiText.text("ui.java.collection_update_ui_service.f06b66585a") + result.issues().size()
                                        + UiText.text("ui.java.collection_update_ui_service.6cc0905a53") + result.errors()
                                        + UiText.text("ui.java.collection_update_ui_service.57d4facd0c") + formatDuration(result.durationMs())
                                        + issueSummary);
                        if (onDone != null) onDone.run();
                    }
                }));
    }

    public void autoUpdateOnStartup(Window owner, Runnable onDone) {
        if (!startupCheckStarted.compareAndSet(false, true)) return;
        if (!settings.getBoolean("online.autoUpdateOnStartup", false)) return;

        Collection collection = state.getCurrentLibraryCollection();
        if (collection == null || !isOnlineCollection(collection)) return;
        String source = resolveCatalogSource(collection);
        if (source == null || source.isBlank()) return;

        AtomicBoolean flag = new AtomicBoolean(false);
        if (active != null) return;
        active = flag;
        state.getStatusBar().setProgressVisible(true);
        state.getStatusBar().setProgress(0.0);
        state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.1b594dba4a"));

        executor.submit(() -> useCase.execute(
                        collection, source, flag,
                        p -> Platform.runLater(() -> {
                            if (isActiveCollection(collection.getId())) state.getStatusBar().setProgress(p);
                        }),
                        progress -> operationCenter.accept(UiText.text("ui.java.collection_update_ui_service.9ca3b3d4c9"), collection.getId(), progress)))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (active == flag) active = null;
                    if (!isActiveCollection(collection.getId())) return;
                    state.getStatusBar().setProgressVisible(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        if (!flag.get()) {
                            if (cause instanceof CatalogUpdateFailureException failure) {
                                state.getStatusBar().setStatusText(updateFailureStatus(failure));
                            } else {
                                state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.f51db91145") + UiExceptionSupport.message(cause));
                            }
                        }
                        return;
                    }
                    if (result == null || (result.imported() == 0 && result.errors() == 0)) {
                        state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.b5482bef6a"));
                        return;
                    }
                    state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.c348f5aa02") + result.imported() + UiText.text("ui.java.collection_update_ui_service.c06658197d"));
                    if (onDone != null) onDone.run();
                }));
    }

    private static String updateFailureStatus(CatalogUpdateFailureException failure) {
        if (failure == null) return UiText.text("ui.java.collection_update_ui_service.94eef88a08");
        if (failure.mutationMayHaveCommitted() && failure.rollbackSucceeded()) {
            return UiText.text("ui.java.collection_update_ui_service.d8c23b570e");
        }
        if (failure.mutationMayHaveCommitted()) {
            return UiText.text("ui.java.collection_update_ui_service.4d6a08d557");
        }
        return UiText.text("ui.java.collection_update_ui_service.eac038a17c") + stageText(failure.stage());
    }

    private static String updateFailureDetails(CatalogUpdateFailureException failure) {
        String source = failure.source().isBlank() ? "—" : failure.source();
        String version = failure.lastAppliedVersion().isBlank() ? "—" : failure.lastAppliedVersion();
        String localState;
        if (!failure.mutationMayHaveCommitted()) {
            localState = UiText.text("ui.java.collection_update_ui_service.64bdccecdf");
        } else if (failure.rollbackSucceeded()) {
            localState = UiText.text("ui.java.collection_update_ui_service.71fc3d7c57");
        } else if (failure.rollbackAttempted()) {
            localState = UiText.text("ui.java.collection_update_ui_service.d6d419c1d7");
        } else {
            localState = UiText.text("ui.java.collection_update_ui_service.496ca6b3bc");
        }
        return UiText.text("ui.java.collection_update_ui_service.cba0698791")
                + UiText.text("ui.java.collection_update_ui_service.c5336b90c9") + stageText(failure.stage())
                + UiText.text("ui.java.collection_update_ui_service.bc4327c7ce") + source
                + UiText.text("ui.java.collection_update_ui_service.37dda9a987") + rootMessage(failure)
                + UiText.text("ui.java.collection_update_ui_service.05c04ee8d7") + version
                + UiText.text("ui.java.collection_update_ui_service.e826ebcdb6") + localState;
    }

    private static String rootMessage(Throwable error) {
        return UiExceptionSupport.message(error);
    }

    private static String stageText(OperationStage stage) {
        if (stage == null) return UiText.text("ui.java.collection_update_ui_service.88d0c9e046");
        return switch (stage) {
            case CHECKING_SERVER -> UiText.text("ui.java.collection_update_ui_service.fd2bc82372");
            case DOWNLOADING -> UiText.text("ui.java.collection_update_ui_service.7b9c03e36a");
            case VALIDATING -> UiText.text("ui.java.collection_update_ui_service.7646de6b30");
            case CREATING_CHECKPOINT -> UiText.text("ui.java.collection_update_ui_service.53c49f80c9");
            case READING_CATALOG -> UiText.text("ui.java.collection_update_ui_service.697fb3bd1a");
            case IMPORTING -> UiText.text("ui.java.collection_update_ui_service.1eba1061bd");
            case UPDATING_AUTHORS -> UiText.text("ui.java.collection_update_ui_service.f90066a538");
            case APPLYING_DELETIONS -> UiText.text("ui.java.collection_update_ui_service.5db574945c");
            case UPDATING_SEARCH_INDEX -> UiText.text("ui.java.collection_update_ui_service.027dac2425");
            case REFRESHING_STATISTICS -> UiText.text("ui.java.collection_update_ui_service.62e8018210");
            case ROLLING_BACK -> UiText.text("ui.java.collection_update_ui_service.8a48c91ec7");
            case FINALIZING -> UiText.text("ui.java.collection_update_ui_service.226e6fe4d3");
            case INTEGRITY_CHECKS -> UiText.text("ui.java.collection_update_ui_service.cc9c7e7ace");
            case SCANNING_DUPLICATES -> UiText.text("ui.java.collection_update_ui_service.17bd166fdc");
            case SYNCHRONIZING_FILES -> UiText.text("ui.java.collection_update_ui_service.e94e14ff09");
            case OPTIMIZING_DATABASE -> UiText.text("ui.java.collection_update_ui_service.f1f3fed545");
            case BACKING_UP -> UiText.text("ui.java.collection_update_ui_service.a963bf2a71");
            case RESTORING -> UiText.text("ui.java.collection_update_ui_service.1913bf9307");
            case CREATING_COLLECTION -> UiText.text("ui.java.collection_update_ui_service.3a7f411870");
            case DELETING_COLLECTION -> UiText.text("ui.java.collection_update_ui_service.925caf3458");
            case BOOK_DOWNLOAD -> UiText.text("ui.java.collection_update_ui_service.f13ae0ef55");
            case CONVERTING -> UiText.text("ui.java.collection_update_ui_service.8fda8d73e2");
            case COMPLETED -> UiText.text("ui.java.collection_update_ui_service.c75e046b49");
            case CANCELLED -> UiText.text("ui.java.collection_update_ui_service.5283a27359");
            case FAILED -> UiText.text("ui.java.collection_update_ui_service.5d7bd00d31");
        };
    }

    private boolean isActiveCollection(String collectionId) {
        Collection activeCollection = state.getCurrentLibraryCollection();
        return activeCollection != null && java.util.Objects.equals(collectionId, activeCollection.getId());
    }

    private String resolveCatalogSource(Collection collection) {
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) return "";
        String sourceKey = "collection." + collection.getId() + ".inpxUrl";
        String configured = settings.get(sourceKey, "").trim();
        if (!configured.isBlank()) return configured;

        String collectionUrl = collection.getUrl() == null ? "" : collection.getUrl().trim();
        String lower = collectionUrl.toLowerCase(java.util.Locale.ROOT);
        if (!collectionUrl.isBlank() && (lower.endsWith(".inpx") || lower.endsWith(".zip") || lower.contains("alex80.github.io/mhl"))) {
            return collectionUrl;
        }
        if (CollectionType.fromCode(collection.getType()) == CollectionType.REMOTE) return DEFAULT_FLIBUSTA_INPX_SERVER;
        return "";
    }

    private static boolean isOnlineCollection(Collection collection) {
        if (collection == null) return false;
        CollectionType type = CollectionType.fromCode(collection.getType());
        return type == CollectionType.REMOTE || type == CollectionType.GENERIC_REMOTE
                || (collection.getUrl() != null && !collection.getUrl().isBlank())
                || (collection.getConnectionScript() != null && !collection.getConnectionScript().isBlank());
    }

    public boolean cancel() {
        AtomicBoolean flag = active;
        if (flag == null) return false;
        flag.set(true);
        state.getStatusBar().setStatusText(UiText.text("ui.java.collection_update_ui_service.2cee850aef"));
        return true;
    }

    private static String issueCodeSummary(com.myhomelibcorp.application.imports.statistics.ImportResult result) {
        if (result == null || result.issues() == null || result.issues().isEmpty()) return "";
        java.util.Map<String, Long> counts = result.issues().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        issue -> issue.code() == null || issue.code().isBlank() ? "UNCLASSIFIED" : issue.code(),
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
        StringBuilder out = new StringBuilder(UiText.text("ui.java.collection_update_ui_service.6c5a2551a4"));
        int shown = 0;
        for (var entry : counts.entrySet()) {
            if (shown++ >= 8) {
                out.append(UiText.text("ui.java.collection_update_ui_service.e83f2928da")).append(counts.size() - 8).append(UiText.text("ui.java.collection_update_ui_service.0c551fb386"));
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
        if (minutes > 0) return minutes + UiText.text("ui.java.collection_update_ui_service.b334dcbfae") + seconds + UiText.text("ui.java.collection_update_ui_service.488d98cad9");
        if (totalSeconds > 0) return totalSeconds + "." + String.format(java.util.Locale.ROOT, "%03d", remMillis) + UiText.text("ui.java.collection_update_ui_service.488d98cad9");
        return Math.max(0L, millis) + UiText.text("ui.java.collection_update_ui_service.5160aea6bb");
    }

}
