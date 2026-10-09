package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.scene.control.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OperationCenterActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void onRefreshOnCancelSelectedAndOnClearCompletedReachOperationCenterBoundary() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(OperationCenterController.class);
        OperationCenterController c = f.instance();
        TableView<OperationCenterEntry> table = UiActionTestSupport.fx(TableView::new);
        UiActionTestSupport.setField(c, "operationsTable", table);
        UiActionTestSupport.setField(c, "summaryLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(c, "detailsArea", UiActionTestSupport.fx(TextArea::new));
        UiActionTestSupport.setField(c, "cancelButton", UiActionTestSupport.fx(Button::new));
        OperationCenterService service = f.dependency(OperationCenterService.class);
        OperationCenterEntry entry = new OperationCenterEntry("op-1", "Import", "c1", OperationKind.CATALOG_IMPORT,
                OperationStage.IMPORTING, 1, 10, 0,0,0,0,0,0,0,"file.fb2", true,
                Instant.now(), Instant.now(), null, "");
        when(service.snapshot()).thenReturn(List.of(entry));
        when(service.canRequestCancellation("op-1")).thenReturn(true);

        UiActionTestSupport.invoke(c, "onRefresh");
        @SuppressWarnings("unchecked") javafx.collections.ObservableList<OperationCenterEntry> rows =
                (javafx.collections.ObservableList<OperationCenterEntry>) UiActionTestSupport.getField(c, "rows");
        assertThat(rows).hasSize(1);
        UiActionTestSupport.fx(() -> { table.getItems().setAll(entry); table.getSelectionModel().select(entry); return null; });
        UiActionTestSupport.invoke(c, "onCancelSelected");
        verify(service).requestCancellation("op-1");
        UiActionTestSupport.invoke(c, "onClearCompleted");
        verify(service).clearCompleted();
    }
}
