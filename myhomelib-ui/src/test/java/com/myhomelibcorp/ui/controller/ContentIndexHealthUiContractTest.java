package com.myhomelibcorp.ui.controller;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContentIndexHealthUiContractTest {
    @Test
    void libraryHealthExposesContentIndexDiagnosticsProgressAndSeparateRebuild() throws Exception {
        String fxml;
        try (var in = getClass().getResourceAsStream("/view/integrity-check.fxml")) {
            assertThat(in).isNotNull();
            fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(fxml).contains("contentIndexValue", "contentIndexDetailLabel", "contentIndexProgress",
                "contentIndexRebuildButton", "contentIndexCancelButton",
                "#onShowContentIndex", "#onRebuildContentIndex", "#onCancelContentIndexRebuild");

        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/controller/IntegrityCheckController.java"));
        assertThat(controller).contains("ContentIndexMaintenanceService", "health.schemaVersion()",
                "health.documentCount()", "health.sizeBytes()", "contentIndexMaintenance.rebuild",
                "contentIndexCancel::get", "updateContentProgress",
                "Індекс метаданих перебудовується окремо через «Інструменти бази даних»");
    }
}
