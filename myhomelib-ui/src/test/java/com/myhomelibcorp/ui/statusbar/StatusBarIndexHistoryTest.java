package com.myhomelibcorp.ui.statusbar;

import com.myhomelibcorp.application.operation.LibraryOperationCompletion;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationOutcome;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StatusBarIndexHistoryTest {
    @Test
    void invisibleStartupRebuildIsMirroredOnceAndCompletedInOperations() {
        OperationCenterService operations = new OperationCenterService();
        StatusBarController controller = new StatusBarController(
                mock(ApplicationState.class), mock(StatisticsService.class),
                mock(UiBackgroundExecutor.class), operations,
                mock(LibraryOperationCoordinator.class), mock(WorkspaceManager.class));
        controller.startIndexHistoryIfMissing();
        controller.startIndexHistoryIfMissing();
        assertEquals(1, operations.snapshot().size());
        assertTrue(operations.snapshot().getFirst().active());
        assertEquals(OperationKind.INDEX_REBUILD, operations.snapshot().getFirst().kind());
        controller.finishIndexHistory(new LibraryOperationCompletion(
                LibraryOperationType.INDEX, LibraryOperationOutcome.COMPLETED, ""));
        assertEquals(1, operations.snapshot().size());
        assertFalse(operations.snapshot().getFirst().active());
    }
}
