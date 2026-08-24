package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.presenter.GroupPresenter;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupController {

    private final GroupPresenter groupPresenter;
    private final ApplicationState appState;
    private final GroupRepository groupRepository;
    private final DialogService dialogService;

    public void handleAddGroup(Runnable onComplete) {
        groupPresenter.showAddGroupDialog(null, onComplete);
    }

    public void handleEditGroup(Runnable onComplete) {
        Group current = appState.getCurrentGroup();
        if (current == null) {
            return;
        }
        groupPresenter.showEditGroupDialog(null, onComplete);
    }

    public void handleDeleteGroup(Runnable onComplete) {
        Group current = appState.getCurrentGroup();
        if (current == null) {
            return;
        }
        groupPresenter.showDeleteGroupDialog(null, onComplete);
    }

    public void handleClearGroup(Runnable onComplete) {
        Group current = appState.getCurrentGroup();
        if (current == null) { dialogService.showWarning("Немає групи", "Спочатку виберіть групу."); return; }
        if (!dialogService.showConfirmation("Очистити групу?", current.getName(), "Книги залишаться у каталозі, буде видалено лише членство у групі.")) return;
        groupRepository.deleteAllBooksFromGroup(current.getId().asLong());
        if (onComplete != null) onComplete.run();
    }
}