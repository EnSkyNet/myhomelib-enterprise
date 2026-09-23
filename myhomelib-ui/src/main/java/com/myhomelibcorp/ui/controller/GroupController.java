package com.myhomelibcorp.ui.controller;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.application.usecase.group.ClearGroupUseCase;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.presenter.GroupPresenter;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupController {

    private final GroupPresenter groupPresenter;
    private final ApplicationState appState;
    private final ClearGroupUseCase clearGroupUseCase;
    private final DialogService dialogService;

    public void handleAddGroup(Runnable onComplete) {
        groupPresenter.showAddGroupDialog(onComplete);
    }

    public void handleEditGroup(Runnable onComplete) {
        Group current = appState.getCurrentGroup();
        if (current == null) {
            return;
        }
        groupPresenter.showEditGroupDialog(current, onComplete);
    }

    public void handleDeleteGroup(Runnable onComplete) {
        Group current = appState.getCurrentGroup();
        if (current == null) {
            return;
        }
        groupPresenter.showDeleteGroupDialog(current, onComplete);
    }

    public void handleClearGroup(Runnable onComplete) {
        Group current = appState.getCurrentGroup();
        String expectedCollectionId = currentCollectionId();
        if (current == null) { dialogService.showWarning(UiText.text("ui.java.group_controller.36d06fcc7d"), UiText.text("ui.java.group_controller.9f8ef2459e")); return; }
        if (!dialogService.showConfirmation(UiText.text("ui.java.group_controller.74addb39d6"), current.getName(), UiText.text("ui.java.group_controller.637dcd5942"))) return;
        clearGroupUseCase.execute(current.getId().asLong(), expectedCollectionId);
        if (onComplete != null) onComplete.run();
    }
    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }

}