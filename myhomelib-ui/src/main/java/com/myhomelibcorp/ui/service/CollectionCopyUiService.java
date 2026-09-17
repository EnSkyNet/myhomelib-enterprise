package com.myhomelibcorp.ui.service;

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

import java.util.List;

@Service
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
            dialogs.showWarning("Немає книг", "Позначте книги прапорцями для копіювання.");
            return;
        }
        Collection current = state.getCurrentLibraryCollection();
        executor.submit(() -> collections.findAll().stream()
                        .filter(c -> current == null || !c.getId().equals(current.getId()))
                        .toList())
                .whenComplete((targets, loadError) -> UiExecutor.runOnUiThread(() -> {
                    if (loadError != null) {
                        dialogs.showError("Помилка копіювання", UiExceptionMessages.root(loadError));
                        return;
                    }
                    if (targets == null || targets.isEmpty()) {
                        dialogs.showWarning("Немає цільової колекції", "Створіть ще одну колекцію.");
                        return;
                    }
                    ChoiceDialog<Collection> dialog = new ChoiceDialog<>(targets.get(0), targets);
                    dialog.setTitle("Копіювати між колекціями");
                    dialog.setHeaderText("Цільова колекція");
                    dialog.setContentText("Колекція:");
                    if (owner != null) dialog.initOwner(owner);
                    Collection target = dialog.showAndWait().orElse(null);
                    if (target != null) copyToTarget(ids, target, onComplete);
                }));
    }

    private void copyToTarget(List<BookId> ids, Collection target, Runnable onComplete) {
        executor.submit(() -> copy.execute(ids, target.getId()))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        dialogs.showError("Помилка копіювання", UiExceptionMessages.root(error));
                        return;
                    }
                    String message = "Скопійовано: " + result.copied() + "; помилок: " + result.failed();
                    if (!result.errors().isEmpty()) {
                        message += "\n\n" + String.join("\n", result.errors().stream().limit(10).toList());
                    }
                    dialogs.showInfo("Копіювання завершено", message);
                    if (onComplete != null) onComplete.run();
                }));
    }

}
