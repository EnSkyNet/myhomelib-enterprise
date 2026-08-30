package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.usecase.group.CreateGroupUseCase;
import com.myhomelibcorp.application.usecase.group.DeleteGroupUseCase;
import com.myhomelibcorp.application.usecase.group.RenameGroupUseCase;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
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
    private final NavigationPanelController navigationPanelController;

    public void showAddGroupDialog(Runnable onComplete) {
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
                    appState.getStatusBar().setStatusText("Групу '" + name + "' створено");
                    navigationPanelController.refreshAll();
                    if (onComplete != null) onComplete.run();
                } catch (Exception e) {
                    dialogService.showError("Помилка", e.getMessage());
                }
            }
        });
    }

    public void showEditGroupDialog(Group selected, Runnable onComplete) {
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
                    appState.getStatusBar().setStatusText("Групу перейменовано на '" + newName + "'");
                    navigationPanelController.refreshAll();
                    if (onComplete != null) onComplete.run();
                } catch (Exception e) {
                    dialogService.showError("Помилка", e.getMessage());
                }
            }
        });
    }

    public void showDeleteGroupDialog(Group selected, Runnable onComplete) {
        if (selected == null) {
            dialogService.showError("Помилка", "Не вибрано жодної групи");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Цю групу не можна видаляти (системна)");
            return;
        }
        if (dialogService.showConfirmation(
                "Підтвердження",
                "Видалити групу '" + selected.getName() + "'?",
                "Книги не будуть видалені, але зв'язок буде втрачено."
        )) {
            try {
                deleteGroupUseCase.execute(selected.getId().asLong());
                appState.getStatusBar().setStatusText("Групу видалено");
                navigationPanelController.refreshAll();
                if (onComplete != null) onComplete.run();
            } catch (Exception e) {
                dialogService.showError("Помилка", e.getMessage());
            }
        }
    }

}
