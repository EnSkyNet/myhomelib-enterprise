package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.StatusBarViewModel;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static com.myhomelibcorp.ui.support.UiActionTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CollectionWorkspaceActionContractTest {
    @BeforeAll static void fxStart() throws Exception { startFx(); }

    @Test
    void panelHandlersRouteToExactPanelCoordinator() throws Exception {
        var f = constructWithMocks(CollectionWorkspaceController.class);
        CollectionWorkspaceController c = f.instance();
        CollectionDto selected = mock(CollectionDto.class);
        setField(c, "selectedCollection", selected);

        invoke(c, "onBrowseSource");
        verify(f.dependency(CollectionSourcePanelCoordinator.class)).browse(selected);
        invoke(c, "onSaveSourceMonitor");
        verify(f.dependency(CollectionSourcePanelCoordinator.class)).save(selected);
        invoke(c, "onBrowseIncomingFolder");
        verify(f.dependency(IncomingFolderPanelCoordinator.class)).browse(selected);
        invoke(c, "onSaveIncomingFolder");
        verify(f.dependency(IncomingFolderPanelCoordinator.class)).save(selected);
        invoke(c, "onCheckSourceNow");
        verify(f.dependency(CollectionSourcePanelCoordinator.class)).checkNow(selected);
        invoke(c, "onScanIncomingFolder");
        verify(f.dependency(IncomingFolderPanelCoordinator.class)).scanNow(selected);
        invoke(c, "onAnalyzeMaintenance");
        verify(f.dependency(CollectionMaintenancePanelCoordinator.class)).analyze(selected);
        invoke(c, "onDryRunMaintenance");
        verify(f.dependency(CollectionMaintenancePanelCoordinator.class)).dryRun(selected);
        invoke(c, "onApplyMaintenance");
        verify(f.dependency(CollectionMaintenancePanelCoordinator.class)).apply(selected);
    }


    @Test
    void onActivateCollectionStartsActivationOnlyForInactiveSelection() throws Exception {
        var f = constructWithMocks(CollectionWorkspaceController.class);
        CollectionWorkspaceController c = f.instance();
        CollectionDto selected = mock(CollectionDto.class);
        when(selected.isActive()).thenReturn(false);
        when(selected.getName()).thenReturn("Library");
        when(selected.getId()).thenReturn("collection-1");
        setField(c, "selectedCollection", selected);
        setField(c, "collectionsListView", fx(ListView::new));
        when(f.dependency(ApplicationState.class).getStatusBar()).thenReturn(new StatusBarViewModel());
        when(f.dependency(UiBackgroundExecutor.class).submit(any(java.util.concurrent.Callable.class)))
                .thenReturn(new CompletableFuture<>());

        invoke(c, "onActivateCollection");

        verify(f.dependency(UiBackgroundExecutor.class)).submit(any(java.util.concurrent.Callable.class));
    }

    @Test
    void renameActionValidatesCurrentSelectionBeforeMutation() throws Exception {
        var f = constructWithMocks(CollectionWorkspaceController.class);
        CollectionWorkspaceController c = f.instance();
        ListView<CollectionDto> list = fx(ListView::new);
        setField(c, "collectionsListView", list);
        invoke(c, "onRenameCollection");
        verify(f.dependency(DialogService.class)).showError(anyString(), anyString());
    }

    @Test
    void refreshContextActionUsesLoadCollectionsAsyncBoundary() {
        var f = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> constructWithMocks(CollectionWorkspaceController.class));
        UiBackgroundExecutor executor = f.dependency(UiBackgroundExecutor.class);
        when(executor.submit(any(java.util.concurrent.Callable.class))).thenReturn(new CompletableFuture<>());
        f.instance().loadCollections();
        verify(executor).submit(any(java.util.concurrent.Callable.class));
    }
}
