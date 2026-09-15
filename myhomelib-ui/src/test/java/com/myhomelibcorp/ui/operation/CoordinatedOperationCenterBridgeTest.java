package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoordinatedOperationCenterBridgeTest {

    @Test
    void automaticIndexRebuildAppearsInHistoryAndCompletes() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        OperationCenterService center = new OperationCenterService();
        ApplicationState state = new ApplicationState();
        state.setCurrentLibraryCollection(new Collection("c1", "Моя бібліотека", null, "library.db", 0, null, null, null, null));
        CoordinatedOperationCenterBridge bridge = new CoordinatedOperationCenterBridge(coordinator, center, state);
        bridge.start();
        try {
            var lease = coordinator.acquireDetached(LibraryOperationType.INDEX);
            assertThat(center.snapshot()).hasSize(1);
            OperationCenterEntry active = center.snapshot().getFirst();
            assertThat(active.active()).isTrue();
            assertThat(active.kind()).isEqualTo(OperationKind.INDEX_REBUILD);
            assertThat(active.stage()).isEqualTo(OperationStage.UPDATING_SEARCH_INDEX);
            assertThat(active.collectionId()).isEqualTo("c1");

            lease.close();
            OperationCenterEntry finished = center.snapshot().getFirst();
            assertThat(finished.active()).isFalse();
            assertThat(finished.stage()).isEqualTo(OperationStage.COMPLETED);
            assertThat(finished.finishedAt()).isNotNull();
        } finally {
            bridge.stop();
        }
    }

    @Test
    void doesNotDuplicateIndexAlreadyRepresentedByDetailedProgress() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        OperationCenterService center = new OperationCenterService();
        ApplicationState state = new ApplicationState();
        center.start("Перебудова Lucene", "c1", OperationKind.INDEX_REBUILD, OperationStage.UPDATING_SEARCH_INDEX, false);
        CoordinatedOperationCenterBridge bridge = new CoordinatedOperationCenterBridge(coordinator, center, state);
        bridge.start();
        try (var ignored = coordinator.acquire(LibraryOperationType.INDEX)) {
            assertThat(center.snapshot()).hasSize(1);
        } finally {
            bridge.stop();
        }
    }
    @Test
    void automaticIndexFailureIsPreservedInHistory() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        OperationCenterService center = new OperationCenterService();
        ApplicationState state = new ApplicationState();
        CoordinatedOperationCenterBridge bridge = new CoordinatedOperationCenterBridge(coordinator, center, state);
        bridge.start();
        try {
            var lease = coordinator.acquireDetached(LibraryOperationType.INDEX);
            lease.markFailed(new IllegalStateException("Lucene write failed"));
            lease.close();

            OperationCenterEntry finished = center.snapshot().getFirst();
            assertThat(finished.stage()).isEqualTo(OperationStage.FAILED);
            assertThat(finished.errorMessage()).contains("Lucene write failed");
        } finally {
            bridge.stop();
        }
    }

    @Test
    void folderSyncRemainsInHistoryWithTerminalSummary() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        OperationCenterService center = new OperationCenterService();
        ApplicationState state = new ApplicationState();
        CoordinatedOperationCenterBridge bridge = new CoordinatedOperationCenterBridge(coordinator, center, state);
        bridge.start();
        try {
            var lease = coordinator.acquireDetached(LibraryOperationType.SYNC);
            assertThat(center.snapshot()).hasSize(1);
            assertThat(center.snapshot().getFirst().active()).isTrue();

            lease.markCompleted("Синхронізовано 42 файли");
            lease.close();

            OperationCenterEntry finished = center.snapshot().getFirst();
            assertThat(finished.stage()).isEqualTo(OperationStage.COMPLETED);
            assertThat(finished.currentItem()).isEqualTo("Синхронізовано 42 файли");
            assertThat(finished.finishedAt()).isNotNull();
        } finally {
            bridge.stop();
        }
    }

}
