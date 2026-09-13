package com.myhomelibcorp.ui.metadata;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkMetadataEditUiContractTest {
    @Test
    void mainMenuExposesLocalBatchEditAndSharedUndoHandlers() throws Exception {
        String fxml = Files.readString(Path.of("src/main/resources/view/MainView.fxml"));
        assertTrue(fxml.contains("#handleLocalBatchMetadata"));
        assertTrue(fxml.contains("#handleUndoLastLibraryOperation"));
    }

    @Test
    void uiUsesBatchUseCaseAndSharedHistoryRatherThanDirectPersistence() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/metadata/BulkMetadataEditUiService.java"));
        assertTrue(source.contains("BatchMetadataEditUseCase"));
        assertTrue(source.contains("LibraryOperationHistoryUseCase"));
        assertTrue(source.contains("UiAsyncRequestGuard"));
        assertTrue(source.contains("AtomicBoolean"));
    }
}
