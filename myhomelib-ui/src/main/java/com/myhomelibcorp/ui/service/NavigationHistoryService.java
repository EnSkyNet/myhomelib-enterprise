package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class NavigationHistoryService {

    private final WorkspaceManager workspaceManager;
    private MainController mainController;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    public void goBack() {
        workspaceManager.goBack();
        if (mainController != null) {
            mainController.updateNavigationButtons();
        }
    }

    public void goForward() {
        workspaceManager.goForward();
        if (mainController != null) {
            mainController.updateNavigationButtons();
        }
    }

    public boolean canGoBack() {
        return workspaceManager.canGoBack();
    }

    public boolean canGoForward() {
        return workspaceManager.canGoForward();
    }
}