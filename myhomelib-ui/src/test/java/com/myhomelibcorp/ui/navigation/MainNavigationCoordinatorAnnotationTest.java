package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.service.ReadingHistoryService;
import com.myhomelibcorp.ui.service.ClassicLibraryActionsService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class MainNavigationCoordinatorAnnotationTest {

    @Test
    void annotationsDisposesActiveReaderBeforeLoadingManagerWorkspace() {
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        NavigationPanelController navigationPanel = mock(NavigationPanelController.class);
        MainNavigationCoordinator coordinator = new MainNavigationCoordinator(
                workspaceManager,
                navigationPanel,
                mock(ClassicLibraryActionsService.class),
                mock(ReadingHistoryService.class),
                mock(DialogService.class),
                mock(LocalizationService.class));

        coordinator.annotations();

        var order = inOrder(workspaceManager);
        order.verify(workspaceManager).disposeCurrentReaderIfActive();
        order.verify(workspaceManager).showAnnotationManagerWorkspace();
        order.verifyNoMoreInteractions();
    }
}
