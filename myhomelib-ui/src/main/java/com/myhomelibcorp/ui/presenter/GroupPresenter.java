package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.usecase.group.CreateGroupUseCase;
import com.myhomelibcorp.application.usecase.group.DeleteGroupUseCase;
import com.myhomelibcorp.application.usecase.group.RenameGroupUseCase;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupPresenter {

    private final CreateGroupUseCase createGroupUseCase;
    private final RenameGroupUseCase renameGroupUseCase;
    private final DeleteGroupUseCase deleteGroupUseCase;
    private final DialogService dialogService;
    private final ApplicationState appState;

    public void showAddGroupDialog(ListView<Group> groupsListView, Runnable onComplete) {
        Optional<String> result = dialogService.showTextInput(
                "Додати групу",
                "Введіть назву нової групи",
                "Назва:",
                ""
        );
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    createGroupUseCase.execute(name);
                    refreshGroupList(groupsListView);
                    appState.getStatusBar().setStatusText("Групу '" + name + "' створено");
                    if (onComplete != null) onComplete.run();
                } catch (Exception e) {
                    dialogService.showError("Помилка", e.getMessage());
                }
            }
        });
    }

    public void showEditGroupDialog(ListView<Group> groupsListView, Runnable onComplete) {
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Цю групу не можна перейменовувати (системна)");
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                "Редагування групи",
                "Введіть нову назву групи",
                "Назва:",
                selected.getName()
        );
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    renameGroupUseCase.execute(selected.getId().asLong(), newName);
                    refreshGroupList(groupsListView);
                    appState.getStatusBar().setStatusText("Групу перейменовано на '" + newName + "'");
                    if (onComplete != null) onComplete.run();
                } catch (Exception e) {
                    dialogService.showError("Помилка", e.getMessage());
                }
            }
        });
    }

    public void showDeleteGroupDialog(ListView<Group> groupsListView, Runnable onComplete) {
        Group selected = groupsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Цю групу не можна видалити (системна)");
            return;
        }
        if (dialogService.showConfirmation(
                "Підтвердження",
                "Видалити групу '" + selected.getName() + "'?",
                "Книги не будуть видалені, але зв'язок буде втрачено."
        )) {
            try {
                deleteGroupUseCase.execute(selected.getId().asLong());
                refreshGroupList(groupsListView);
                appState.getStatusBar().setStatusText("Групу видалено");
                if (onComplete != null) onComplete.run();
            } catch (Exception e) {
                dialogService.showError("Помилка", e.getMessage());
            }
        }
    }

    private void refreshGroupList(ListView<Group> groupsListView) {
        // Оновлення списку груп відбувається через NavigationViewModel, який оновлюється в NavigationController
        // Можна викликати перезавантаження через сервіс
    }
}