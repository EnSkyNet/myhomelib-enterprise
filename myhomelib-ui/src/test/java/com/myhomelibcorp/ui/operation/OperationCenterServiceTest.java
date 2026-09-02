package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OperationCenterServiceTest {

    @Test
    void keepsStartedAtAndLatestTelemetryUntilTerminalState() {
        OperationCenterService service = new OperationCenterService();
        service.accept("Import", "c1", OperationProgress.stage("op-1", OperationStage.IMPORTING, true)
                .withProgress(10, 100));
        var started = service.snapshot().getFirst().startedAt();

        service.accept("Import", "c1", OperationProgress.stage("op-1", OperationStage.UPDATING_SEARCH_INDEX, false)
                .withProgress(80, 100)
                .withCounts(4, 76, 0, 0, 0, 0, 0));
        service.complete("op-1", "done");

        OperationCenterEntry entry = service.snapshot().getFirst();
        assertThat(entry.startedAt()).isEqualTo(started);
        assertThat(entry.stage()).isEqualTo(OperationStage.COMPLETED);
        assertThat(entry.processed()).isEqualTo(80);
        assertThat(entry.updated()).isEqualTo(76);
        assertThat(entry.currentItem()).isEqualTo("done");
        assertThat(entry.finishedAt()).isNotNull();
        assertThat(service.activeCount()).isZero();
    }

    @Test
    void listenersReceiveSnapshotsAndCanBeUnregistered() throws Exception {
        OperationCenterService service = new OperationCenterService();
        AtomicInteger notifications = new AtomicInteger();
        AutoCloseable registration = service.addListener(snapshot -> notifications.incrementAndGet());

        String id = service.start("Backup", "c1", OperationStage.BACKING_UP, false);
        service.complete(id, "ok");
        registration.close();
        service.start("Vacuum", "c1", OperationStage.OPTIMIZING_DATABASE, false);

        assertThat(notifications.get()).isEqualTo(3); // initial snapshot + start + complete
    }

    @Test
    void clearCompletedNeverRemovesActiveOperations() {
        OperationCenterService service = new OperationCenterService();
        String active = service.start("Sync", "c1", OperationStage.SYNCHRONIZING_FILES, false);
        String completed = service.start("Backup", "c1", OperationStage.BACKING_UP, false);
        service.complete(completed, "ok");

        service.clearCompleted();

        assertThat(service.snapshot()).extracting(OperationCenterEntry::operationId).containsExactly(active);
    }
}
