package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LibraryOperationUiTextTest {

    @Test
    void explainsIndexAsBlockingReasonInUserLanguage() {
        String message = LibraryOperationUiText.blockingMessage("Оновлення колекції", LibraryOperationType.INDEX);
        assertTrue(message.contains("оновлення пошукового індексу"));
        assertFalse(message.contains("INDEX"));
    }

    @Test
    void indexProgressIsProjectedToStatusBar() {
        OperationCenterService center = new OperationCenterService();
        String id = center.start("Оновлення індексу", "c1", OperationKind.INDEX_REBUILD,
                OperationStage.UPDATING_SEARCH_INDEX, true);
        center.accept("Оновлення індексу", "c1", OperationKind.INDEX_REBUILD,
                OperationProgress.stage(id, OperationStage.UPDATING_SEARCH_INDEX, true)
                        .withProgress(50, 100));

        OperationCenterEntry entry = center.snapshot().getFirst();
        assertEquals("Оновлення пошукового індексу… 50%", LibraryOperationUiText.entryStatus(entry));
        assertTrue(LibraryOperationUiText.matches(LibraryOperationType.INDEX, entry));
    }
    @Test
    void manualInpxCollectionUpdateCanStillRepresentImportCoordinatorState() {
        OperationCenterService center = new OperationCenterService();
        String id = center.start("Оновлення колекції", "c1", OperationKind.CATALOG_UPDATE,
                OperationStage.IMPORTING, false);
        OperationCenterEntry entry = center.snapshot().getFirst();
        assertTrue(LibraryOperationUiText.matches(LibraryOperationType.IMPORT, entry));
        center.complete(id, "done");
    }

}
