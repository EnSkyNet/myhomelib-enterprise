package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.book.MarkAsReadBatchUseCase;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.application.usecase.book.UpdateProgressBatchUseCase;
import com.myhomelibcorp.application.usecase.book.UpdateRateBatchUseCase;
import com.myhomelibcorp.application.usecase.group.AddToGroupBatchUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchOperationsController {

    private final DialogService dialogService;
    private final BookLoaderService bookLoaderService;
    private final BookSelectionService bookSelectionService;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final UiBackgroundExecutor executor;
    private final BookSaver bookSaver;
    private final ApplicationState appState;

    private final UpdateRateBatchUseCase updateRateBatchUseCase;
    private final UpdateProgressBatchUseCase updateProgressBatchUseCase;
    private final MarkAsReadBatchUseCase markAsReadBatchUseCase;
    private final AddToGroupBatchUseCase addToGroupBatchUseCase;
    private final LoadGroupsUseCase loadGroupsUseCase;


    /** Downloads only books explicitly checked through the shared batch selection source. */
    public boolean handleBatchDownload(Runnable onComplete) {
        return handleBatchDownload(null, onComplete);
    }

    public boolean handleBatchDownload(javafx.stage.Window owner, Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            showNoBatchSelection();
            return true;
        }

        String expectedCollectionId = currentCollectionId();
        bookDownloadCoordinator.downloadBatch(selected, owner).whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrentCollection(expectedCollectionId)) return;
            if (error != null) {
                dialogService.showWarning(UiText.text("ui.import.stage.downloading"), UiText.text("ui.java.batch_operations_controller.1f8f127999") + UiExceptionSupport.message(error));
            } else {
                // Download is a terminal batch action: after processing, checkboxes must not remain stale.
                // This applies to newly downloaded and already-local books alike.
                handleClearSelection();
                if (result != null && result.failed() > 0) {
                    dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.bf2ce8a15d"),
                            UiText.text("ui.java.batch_operations_controller.491361bc90") + result.downloaded() + UiText.text("ui.java.batch_operations_controller.47a7bc80cd") + result.alreadyLocal()
                                    + UiText.text("ui.java.batch_operations_controller.bd8a471be4") + result.failed() + ".");
                }
            }
            if (onComplete != null) onComplete.run();
        }));
        return true;
    }

    public boolean handleBatchRemoveLocal(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            showNoBatchSelection();
            return true;
        }
        String expectedCollectionId = currentCollectionId();
        bookDownloadCoordinator.removeLocalCopies(selected).whenComplete((count, error) -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrentCollection(expectedCollectionId)) return;
            if (error == null) {
                handleClearSelection();
                if (onComplete != null) onComplete.run();
            }
        }));
        return true;
    }

    public boolean handleBatchDelete(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            showNoBatchSelection();
            return true;
        }
        if (!dialogService.showConfirmation(UiText.text("ui.java.batch_operations_controller.026298e83a"),
                UiText.text("ui.java.batch_operations_controller.1de483cf51") + selected.size() + "?",
                UiText.text("ui.java.batch_operations_controller.eb95ca1047"))) return true;
        String expectedCollectionId = currentCollectionId();
        executor.submitCollectionScoped(LibraryOperationType.EDIT, () -> bookSaver.deleteBooks(selected, expectedCollectionId))
                .whenComplete((count, error) -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrentCollection(expectedCollectionId)) return;
            if (error == null) {
                dialogService.showInfo(UiText.text("ui.java.batch_operations_controller.398c7d4f7b"), UiText.text("ui.java.batch_operations_controller.fdcebc8c27") + count);
                handleClearSelection();
                if (onComplete != null) onComplete.run();
            } else {
                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.batch_operations_controller.6019413564") + UiExceptionSupport.message(error));
            }
        }));
        return true;
    }

    public void handleBatchRate(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.ac1e0c3aad"), UiText.text("ui.java.batch_operations_controller.6ebfe27ba5"));
            return;
        }
        List<Integer> rates = List.of(1, 2, 3, 4, 5);
        Optional<Integer> result = dialogService.showChoiceDialog(
                rates, 5, UiText.text("ui.fxml.author_workspace.rating"), UiText.text("ui.java.batch_operations_controller.3618c0f9b1") + selected.size() + UiText.text("ui.java.batch_operations_controller.faf0c7e42c"), UiText.text("ui.java.batch_operations_controller.5caaf09cac"));
        String expectedCollectionId = currentCollectionId();
        result.ifPresent(rate -> executor.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
            updateRateBatchUseCase.execute(selected, rate);
            return null;
        }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrentCollection(expectedCollectionId)) return;
            if (error != null) {
                log.error("Помилка масового оновлення рейтингу", error);
                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.batch_operations_controller.dbb417d13e") + UiExceptionMessages.root(error));
                return;
            }
            dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.batch_operations_controller.52d103e3b8") + selected.size() + UiText.text("ui.java.batch_operations_controller.af17e2b02c"));
            clearSelection();
            if (onComplete != null) onComplete.run();
        })));
    }

    public void handleBatchProgress(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.ac1e0c3aad"), UiText.text("ui.java.batch_operations_controller.6ebfe27ba5"));
            return;
        }
        Optional<String> entered = dialogService.showTextInput(
                UiText.text("ui.java.batch_operations_controller.243b2f7cd9"),
                UiText.text("ui.java.batch_operations_controller.c4fd579f46") + selected.size() + UiText.text("ui.java.batch_operations_controller.faf0c7e42c"),
                UiText.text("ui.java.batch_operations_controller.068af0f723"),
                "50");
        if (entered.isEmpty()) return;

        final int progress;
        try {
            progress = Integer.parseInt(entered.get().trim());
        } catch (NumberFormatException e) {
            dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.0f798fc0fb"), UiText.text("ui.java.batch_operations_controller.5bb8692078"));
            return;
        }
        if (progress < 0 || progress > 100) {
            dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.0f798fc0fb"), UiText.text("ui.java.batch_operations_controller.8079bb9bac"));
            return;
        }

        String expectedCollectionId = currentCollectionId();
        executor.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
            updateProgressBatchUseCase.execute(selected, progress);
            return null;
        }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrentCollection(expectedCollectionId)) return;
            if (error != null) {
                log.error("Помилка масового оновлення прогресу", error);
                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.batch_operations_controller.1db8d3d954") + UiExceptionMessages.root(error));
                return;
            }
            dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.batch_operations_controller.e665226cd9") + progress + UiText.text("ui.java.batch_operations_controller.b7065f2c26") + selected.size() + UiText.text("ui.java.batch_operations_controller.af17e2b02c"));
            clearSelection();
            if (onComplete != null) onComplete.run();
        }));
    }

    public void handleBatchMarkRead(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.ac1e0c3aad"), UiText.text("ui.java.batch_operations_controller.6ebfe27ba5"));
            return;
        }
        if (dialogService.showConfirmation(UiText.text("common.confirmation"), UiText.text("ui.java.batch_operations_controller.9f79208acf"),
                UiText.text("ui.java.batch_operations_controller.4b69ba705c") + selected.size() + UiText.text("ui.java.batch_operations_controller.af17e2b02c"))) {
            String expectedCollectionId = currentCollectionId();
            executor.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
                markAsReadBatchUseCase.execute(selected);
                return null;
            }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
                if (!isCurrentCollection(expectedCollectionId)) return;
                if (error != null) {
                    log.error("Помилка масового позначення прочитаним", error);
                    dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.batch_operations_controller.a46ab42550") + UiExceptionMessages.root(error));
                    return;
                }
                dialogService.showInfo(UiText.text("common.success"), selected.size() + UiText.text("ui.java.batch_operations_controller.d6cb37f8ba"));
                clearSelection();
                if (onComplete != null) onComplete.run();
            }));
        }
    }

    public void handleBatchAddToGroup(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.ac1e0c3aad"), UiText.text("ui.java.batch_operations_controller.6ebfe27ba5"));
            return;
        }
        String expectedCollectionId = currentCollectionId();
        executor.submitCollectionScoped(LibraryOperationType.QUERY, loadGroupsUseCase::execute)
                .whenComplete((groups, loadError) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrentCollection(expectedCollectionId)) return;
                    if (loadError != null) {
                        log.error("Помилка завантаження груп", loadError);
                        dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.batch_operations_controller.e75d77fb01") + UiExceptionMessages.root(loadError));
                        return;
                    }
                    if (groups == null || groups.isEmpty()) {
                        dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.005249e3a0"), UiText.text("ui.java.batch_operations_controller.5cca93bf0a"));
                        return;
                    }
                    Optional<GroupDto> group = dialogService.showChoiceDialog(
                            groups, groups.get(0), UiText.text("ui.fxml.book_workspace.add_to_group"),
                            UiText.text("ui.java.batch_operations_controller.d98bd90873") + selected.size() + UiText.text("ui.java.batch_operations_controller.faf0c7e42c"),
                            UiText.text("ui.java.batch_operations_controller.483dbb9035"), GroupDto::getName);
                    group.ifPresent(g -> executor.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
                        addToGroupBatchUseCase.execute(g.getId(), selected, expectedCollectionId);
                        return null;
                    }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
                        if (!isCurrentCollection(expectedCollectionId)) return;
                        if (error != null) {
                            log.error("Помилка масового додавання до групи", error);
                            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.batch_operations_controller.ae7ef9ed1e") + UiExceptionMessages.root(error));
                            return;
                        }
                        dialogService.showInfo(UiText.text("common.success"), selected.size() + UiText.text("ui.java.batch_operations_controller.7366f63c03") + g.getName() + "».");
                        clearSelection();
                        if (onComplete != null) onComplete.run();
                    })));
                }));
    }

    public void handleClearSelection() {
        bookSelectionService.clear();
    }

    private void clearSelection() {
        handleClearSelection();
        bookLoaderService.reloadLastQuery();
    }

    public List<BookId> getSelectedBookIds() {
        return bookSelectionService.snapshot();
    }

    private void showNoBatchSelection() {
        dialogService.showWarning(UiText.text("ui.java.batch_operations_controller.ac1e0c3aad"),
                UiText.text("ui.java.batch_operations_controller.ea4e147415"));
    }
    private boolean isCurrentCollection(String expectedCollectionId) {
        return java.util.Objects.equals(expectedCollectionId, currentCollectionId());
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }

}