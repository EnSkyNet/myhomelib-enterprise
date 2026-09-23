package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
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
        String expectedCollectionId = currentCollectionId();
        Optional<String> result = dialogService.showTextInput(
                UiText.text("ui.java.group_presenter.6555f6ea09"),
                UiText.text("ui.java.group_presenter.ba16e73b9c"),
                UiText.text("common.name.label"),
                ""
        );
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    createGroupUseCase.execute(name, expectedCollectionId);
                    appState.getStatusBar().setStatusText(UiText.text("ui.java.group_presenter.747d492486") + name + UiText.text("ui.java.group_presenter.eccde05e49"));
                    navigationPanelController.refreshAll();
                    if (onComplete != null) onComplete.run();
                } catch (Exception e) {
                    dialogService.showError(UiText.text("common.error"), UiExceptionSupport.message(e));
                }
            }
        });
    }

    public void showEditGroupDialog(Group selected, Runnable onComplete) {
        if (selected == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_presenter.53783692f8"));
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_presenter.3cd6d02aa7"));
            return;
        }
        String expectedCollectionId = currentCollectionId();
        Optional<String> result = dialogService.showTextInput(
                UiText.text("ui.java.group_presenter.2b1d575701"),
                UiText.text("ui.java.group_presenter.22a0b8e114"),
                UiText.text("common.name.label"),
                selected.getName()
        );
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    renameGroupUseCase.execute(selected.getId().asLong(), newName, expectedCollectionId);
                    appState.getStatusBar().setStatusText(UiText.text("ui.java.group_presenter.ed03f04c9d") + newName + "'");
                    navigationPanelController.refreshAll();
                    if (onComplete != null) onComplete.run();
                } catch (Exception e) {
                    dialogService.showError(UiText.text("common.error"), UiExceptionSupport.message(e));
                }
            }
        });
    }

    public void showDeleteGroupDialog(Group selected, Runnable onComplete) {
        if (selected == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_presenter.53783692f8"));
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.group_presenter.913f5da266"));
            return;
        }
        String expectedCollectionId = currentCollectionId();
        if (dialogService.showConfirmation(
                UiText.text("common.confirmation"),
                UiText.text("ui.java.group_presenter.9d9849c06b") + selected.getName() + "'?",
                UiText.text("ui.java.group_presenter.62b93a432c")
        )) {
            try {
                deleteGroupUseCase.execute(selected.getId().asLong(), expectedCollectionId);
                appState.getStatusBar().setStatusText(UiText.text("ui.java.group_presenter.307b3464aa"));
                navigationPanelController.refreshAll();
                if (onComplete != null) onComplete.run();
            } catch (Exception e) {
                dialogService.showError(UiText.text("common.error"), UiExceptionSupport.message(e));
            }
        }
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }
}
