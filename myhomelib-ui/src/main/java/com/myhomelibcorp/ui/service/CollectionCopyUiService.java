package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.usecase.collection.CopyBooksBetweenCollectionsUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Window;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Service
@Slf4j
public class CollectionCopyUiService {
    private final CollectionRepository collections;
    private final CopyBooksBetweenCollectionsUseCase copy;
    private final ApplicationState state;
    private final DialogService dialogs;
    private final BookSelectionService selection;
    private final UiBackgroundExecutor executor;

    public CollectionCopyUiService(CollectionRepository collections,
                                   CopyBooksBetweenCollectionsUseCase copy,
                                   ApplicationState state,
                                   DialogService dialogs,
                                   BookSelectionService selection,
                                   UiBackgroundExecutor executor) {
        this.collections = collections;
        this.copy = copy;
        this.state = state;
        this.dialogs = dialogs;
        this.selection = selection;
        this.executor = executor;
    }

    public void copySelected(Window owner, Runnable onComplete) {
        List<BookId> ids = selection.snapshot();
        if (ids.isEmpty()) {
            dialogs.showWarning(UiText.text("ui.java.collection_copy_ui_service.63589c6c88"), UiText.text("ui.java.collection_copy_ui_service.4272be63cc"));
            return;
        }
        Collection current = state.getCurrentLibraryCollection();
        String expectedSourceCollectionId = current == null ? null : current.getId();
        if (expectedSourceCollectionId == null || expectedSourceCollectionId.isBlank()) {
            dialogs.showWarning(UiText.text("ui.java.collection_copy_ui_service.63589c6c88"), UiText.text("ui.java.collection_copy_ui_service.4272be63cc"));
            return;
        }
        executor.submit(() -> collections.findAll().stream()
                        .filter(c -> current == null || !c.getId().equals(current.getId()))
                        .toList())
                .whenComplete((targets, loadError) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrentCollection(expectedSourceCollectionId)) return;
                    if (loadError != null) {
                        dialogs.showError(UiText.text("ui.java.collection_copy_ui_service.6d3f995012"), UiExceptionMessages.root(loadError));
                        return;
                    }
                    if (targets == null || targets.isEmpty()) {
                        dialogs.showWarning(UiText.text("ui.java.collection_copy_ui_service.b6f5c23853"), UiText.text("ui.java.collection_copy_ui_service.a37a8bed58"));
                        return;
                    }
                    ChoiceDialog<Collection> dialog = new ChoiceDialog<>(targets.get(0), targets);
                    DialogLocalization.prepare(dialog);
                    dialog.setTitle(UiText.text("ui.java.collection_copy_ui_service.f8194b3869"));
                    dialog.setHeaderText(UiText.text("ui.java.collection_copy_ui_service.7fb086838b"));
                    dialog.setContentText(UiText.text("ui.java.collection_copy_ui_service.38033d49c1"));
                    if (owner != null) dialog.initOwner(owner);
                    Collection target = dialog.showAndWait().orElse(null);
                    if (target != null && isCurrentCollection(expectedSourceCollectionId)) {
                        copyToTarget(ids, target, expectedSourceCollectionId, onComplete);
                    }
                }));
    }

    private void copyToTarget(List<BookId> ids, Collection target, String expectedSourceCollectionId, Runnable onComplete) {
        if (!isCurrentCollection(expectedSourceCollectionId)) return;
        executor.submitCollectionScoped(LibraryOperationType.COPY,
                        () -> copy.execute(ids, target.getId(), expectedSourceCollectionId))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrentCollection(expectedSourceCollectionId)) return;
                    if (error != null) {
                        dialogs.showError(UiText.text("ui.java.collection_copy_ui_service.6d3f995012"), UiExceptionMessages.root(error));
                        return;
                    }
                    String message = UiText.text("ui.java.collection_copy_ui_service.5d3f1ade33") + result.copied() + UiText.text("ui.java.collection_copy_ui_service.c3b31299ce") + result.failed();
                    if (!result.errors().isEmpty()) {
                        log.error("Copy between collections completed with {} failure(s): {}",
                                result.failed(), String.join(" | ", result.errors().stream().limit(10).toList()));
                        message += "\n\n" + UiText.text("common.error.details_in_log");
                    }
                    dialogs.showInfo(UiText.text("ui.java.collection_copy_ui_service.98045facc9"), message);
                    if (onComplete != null) onComplete.run();
                }));
    }

    private boolean isCurrentCollection(String expectedCollectionId) {
        return expectedCollectionId != null
                && expectedCollectionId.equals(state.getCurrentLibraryCollectionId());
    }

}
