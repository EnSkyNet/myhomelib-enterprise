package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionRuntimeStateResolverTest {

    @Test
    void derivesUpdateAndProgressFromOperationLifecycle() {
        OperationCenterService center = new OperationCenterService();
        center.accept("Оновлення каталогу", "collection-1",
                OperationProgress.stage("catalog-update-test", OperationStage.IMPORTING, true)
                        .withProgress(68, 100));

        CollectionRuntimeStatus status = CollectionRuntimeStateResolver.resolve("collection-1", center.snapshot());
        assertEquals(CollectionRuntimeState.UPDATING, status.state());
        assertEquals(0.68, status.fraction(), 0.0001);
    }

    @Test
    void exposesIndexingAndFailureWithoutSecondStateMachine() {
        OperationCenterService center = new OperationCenterService();
        String id = center.start("Перебудова Lucene", "collection-1", OperationKind.INDEX_REBUILD,
                OperationStage.UPDATING_SEARCH_INDEX, false);
        assertEquals(CollectionRuntimeState.INDEXING,
                CollectionRuntimeStateResolver.resolve("collection-1", center.snapshot()).state());

        center.fail(id, new IllegalStateException("index corrupted"));
        CollectionRuntimeStatus failed = CollectionRuntimeStateResolver.resolve("collection-1", center.snapshot());
        assertEquals(CollectionRuntimeState.ERROR, failed.state());
        assertEquals("index corrupted", failed.detail());
    }

    @Test
    void terminalSuccessReturnsReady() {
        OperationCenterService center = new OperationCenterService();
        String id = center.start("Імпорт книг", "collection-1", OperationKind.CATALOG_IMPORT,
                OperationStage.IMPORTING, true);
        center.complete(id, "done");
        assertEquals(CollectionRuntimeState.READY,
                CollectionRuntimeStateResolver.resolve("collection-1", center.snapshot()).state());
    }
}
