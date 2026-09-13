package com.myhomelibcorp.ui.controller;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryHealthUiContractTest {
    @Test
    void dashboardExposesKpisDrillDownAsyncRefreshAndExportActions() throws Exception {
        String fxml;
        try (var in = getClass().getResourceAsStream("/view/integrity-check.fxml")) {
            assertThat(in).isNotNull();
            fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(fxml).contains("Library Health", "#onCheckIntegrity", "#onShowMissing", "#onShowCorrupt",
                "#onShowChanged", "#onShowDuplicates", "#onShowMetadata", "#onShowIndex", "#onShowBackup",
                "#onExportReport", "issueTable", "detailArea");

        String controller;
        try (var in = getClass().getResourceAsStream(
                "/com/myhomelibcorp/ui/controller/IntegrityCheckController.class")) {
            assertThat(in).isNotNull();
        }
        controller = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/myhomelibcorp/ui/controller/IntegrityCheckController.java"), StandardCharsets.UTF_8);
        assertThat(controller).contains("executor.submit(healthService::refresh)", "UiExecutor.runOnUiThread");
    }
}
