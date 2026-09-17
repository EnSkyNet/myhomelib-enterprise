package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OperationCenterServiceTest {

    @Test
    void keepsStartedAtAndLatestTelemetryWithinOneVisibleOperation() {
        OperationCenterService service = new OperationCenterService();
        service.accept("Імпорт", "c1", OperationProgress.stage("op-1", OperationStage.IMPORTING, true)
                .withProgress(10, 100));
        var started = service.snapshot().getFirst().startedAt();

        service.accept("Імпорт", "c1", OperationProgress.stage("op-1", OperationStage.APPLYING_DELETIONS, false)
                .withProgress(80, 100)
                .withCounts(4, 76, 0, 0, 0, 0, 0));
        service.complete("op-1", "Готово");

        OperationCenterEntry entry = service.snapshot().getFirst();
        assertThat(entry.startedAt()).isEqualTo(started);
        assertThat(entry.stage()).isEqualTo(OperationStage.COMPLETED);
        assertThat(entry.processed()).isEqualTo(80);
        assertThat(entry.updated()).isEqualTo(76);
        assertThat(entry.currentItem()).isEqualTo("Готово");
        assertThat(entry.finishedAt()).isNotNull();
        assertThat(service.activeCount()).isZero();
    }

    @Test
    void splitsCompositeWorkflowIntoIndependentVisibleOperations() throws Exception {
        OperationCenterService service = new OperationCenterService();
        service.accept("Оновлення каталогу", "c1", OperationKind.CATALOG_UPDATE,
                OperationProgress.stage("catalog-update-1", OperationStage.IMPORTING, true)
                        .withProgress(100, 100));
        OperationCenterEntry update = service.snapshot().getFirst();
        Thread.sleep(5);

        service.accept("Оновлення каталогу", "c1", OperationKind.CATALOG_UPDATE,
                OperationProgress.stage("catalog-update-1", OperationStage.UPDATING_SEARCH_INDEX, false)
                        .withProgress(20, 500));

        assertThat(service.snapshot()).hasSize(2);
        OperationCenterEntry index = service.snapshot().stream().filter(OperationCenterEntry::active).findFirst().orElseThrow();
        OperationCenterEntry completedUpdate = service.snapshot().stream().filter(entry -> !entry.active()).findFirst().orElseThrow();

        assertThat(completedUpdate.operationId()).isEqualTo(update.operationId());
        assertThat(completedUpdate.title()).isEqualTo("Оновлення каталогу · основний етап");
        assertThat(completedUpdate.finishedAt()).isNotNull();
        assertThat(completedUpdate.duration(Instant.now())).isLessThan(index.duration(Instant.now()).plusSeconds(1));
        assertThat(index.operationId()).isNotEqualTo(completedUpdate.operationId());
        assertThat(index.title()).isEqualTo("Оновлення пошукового індексу");
        assertThat(index.kind()).isEqualTo(OperationKind.INDEX_REBUILD);
        assertThat(index.stage()).isEqualTo(OperationStage.UPDATING_SEARCH_INDEX);
    }

    @Test
    void completionByRootIdFinishesCurrentSplitPhaseOnly() {
        OperationCenterService service = new OperationCenterService();
        String id = service.start("Імпорт каталогу", "c1", OperationKind.CATALOG_IMPORT,
                OperationStage.IMPORTING, true);
        service.accept("Імпорт каталогу", "c1", OperationKind.CATALOG_IMPORT,
                OperationProgress.stage(id, OperationStage.UPDATING_SEARCH_INDEX, false));

        service.complete(id, "Готово");

        assertThat(service.activeCount()).isZero();
        assertThat(service.snapshot()).hasSize(2);
        assertThat(service.snapshot()).anySatisfy(entry -> {
            assertThat(entry.title()).isEqualTo("Оновлення пошукового індексу");
            assertThat(entry.stage()).isEqualTo(OperationStage.COMPLETED);
            assertThat(entry.currentItem()).isEqualTo("Готово");
        });
    }

    @Test
    void separatesIndexStatisticsAndFinalization() {
        OperationCenterService service = new OperationCenterService();
        String root = "catalog-update-phases";
        service.accept("Оновлення каталогу", "c1", OperationKind.CATALOG_UPDATE,
                OperationProgress.stage(root, OperationStage.IMPORTING, true));
        service.accept("Оновлення каталогу", "c1", OperationKind.CATALOG_UPDATE,
                OperationProgress.stage(root, OperationStage.UPDATING_SEARCH_INDEX, false));
        service.accept("Оновлення каталогу", "c1", OperationKind.CATALOG_UPDATE,
                OperationProgress.stage(root, OperationStage.REFRESHING_STATISTICS, false));
        service.accept("Оновлення каталогу", "c1", OperationKind.CATALOG_UPDATE,
                OperationProgress.stage(root, OperationStage.FINALIZING, false));
        service.accept("Оновлення каталогу", "c1", OperationKind.CATALOG_UPDATE,
                OperationProgress.stage(root, OperationStage.COMPLETED, false));

        assertThat(service.snapshot()).hasSize(4);
        assertThat(service.snapshot()).extracting(OperationCenterEntry::title)
                .containsExactlyInAnyOrder(
                        "Оновлення каталогу · основний етап",
                        "Оновлення пошукового індексу",
                        "Оновлення статистики бібліотеки",
                        "Завершення оновлення каталогу");
        assertThat(service.snapshot()).allMatch(entry -> !entry.active() && entry.finishedAt() != null);
    }

    @Test
    void listenersReceiveSnapshotsAndCanBeUnregistered() throws Exception {
        OperationCenterService service = new OperationCenterService();
        AtomicInteger notifications = new AtomicInteger();
        AutoCloseable registration = service.addListener(snapshot -> notifications.incrementAndGet());

        String id = service.start("Резервне копіювання", "c1", OperationStage.BACKING_UP, false);
        service.complete(id, "Готово");
        registration.close();
        service.start("Оптимізація", "c1", OperationStage.OPTIMIZING_DATABASE, false);

        assertThat(notifications.get()).isEqualTo(3); // initial snapshot + start + complete
    }

    @Test
    void clearCompletedNeverRemovesActiveOperations() {
        OperationCenterService service = new OperationCenterService();
        String active = service.start("Синхронізація", "c1", OperationStage.SYNCHRONIZING_FILES, false);
        String completed = service.start("Резервне копіювання", "c1", OperationStage.BACKING_UP, false);
        service.complete(completed, "Готово");

        service.clearCompleted();

        assertThat(service.snapshot()).extracting(OperationCenterEntry::operationId).containsExactly(active);
    }
}
