package com.myhomelibcorp.ui.statusbar;

import com.myhomelibcorp.application.operation.LibraryOperationCompletion;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationOutcome;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.LibraryOperationUiText;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatusBarCoordinatorHistoryTest {
    @Test void longRunningUntrackedEditAppearsOnceAndCompletes() {
        OperationCenterService center = new OperationCenterService();
        LibraryOperationCoordinator coordinator = mock(LibraryOperationCoordinator.class);
        when(coordinator.activeOperation()).thenReturn(LibraryOperationType.EDIT);
        StatusBarController bar = new StatusBarController(mock(ApplicationState.class),
                mock(StatisticsService.class), mock(UiBackgroundExecutor.class), center,
                coordinator, mock(WorkspaceManager.class));
        bar.startUntrackedHistoryIfMissing(LibraryOperationType.EDIT);
        bar.startUntrackedHistoryIfMissing(LibraryOperationType.EDIT);
        assertEquals(1, center.snapshot().size());
        assertTrue(center.snapshot().getFirst().active());
        assertEquals(OperationKind.GENERIC, center.snapshot().getFirst().kind());
        assertTrue(center.snapshot().getFirst().operationId().startsWith("coordinator-"));
        assertEquals(center.snapshot().getFirst().title(),
                LibraryOperationUiText.entryStatus(center.snapshot().getFirst()));
        bar.finishUntrackedHistory(new LibraryOperationCompletion(LibraryOperationType.EDIT,
                LibraryOperationOutcome.COMPLETED, "done"));
        assertFalse(center.snapshot().getFirst().active());
    }

    @Test void completedOrRepresentedCoordinatorOperationDoesNotCreateDuplicate() {
        OperationCenterService center = new OperationCenterService();
        LibraryOperationCoordinator coordinator = mock(LibraryOperationCoordinator.class);
        when(coordinator.activeOperation()).thenReturn(null);
        StatusBarController bar = new StatusBarController(mock(ApplicationState.class),
                mock(StatisticsService.class), mock(UiBackgroundExecutor.class), center,
                coordinator, mock(WorkspaceManager.class));
        bar.startUntrackedHistoryIfMissing(LibraryOperationType.QUERY);
        assertEquals(0, center.snapshot().size());
    }
}
