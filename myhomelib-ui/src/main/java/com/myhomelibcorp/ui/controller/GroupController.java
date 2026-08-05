package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.domain.model.group.Group;
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
}