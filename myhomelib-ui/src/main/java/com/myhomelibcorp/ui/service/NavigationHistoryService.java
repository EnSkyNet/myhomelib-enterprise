package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Thin compatibility facade over WorkspaceManager navigation history. */
@Service
@RequiredArgsConstructor
public class NavigationHistoryService {

    private final WorkspaceManager workspaceManager;

    public void goBack() { workspaceManager.goBack(); }
    public void goForward() { workspaceManager.goForward(); }
    public boolean canGoBack() { return workspaceManager.canGoBack(); }
    public boolean canGoForward() { return workspaceManager.canGoForward(); }
}
