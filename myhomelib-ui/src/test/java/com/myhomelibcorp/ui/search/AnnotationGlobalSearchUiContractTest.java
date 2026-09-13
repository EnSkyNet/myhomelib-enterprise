package com.myhomelibcorp.ui.search;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationGlobalSearchUiContractTest {
    @Test
    void globalSearchQueriesAnnotationsAndCanJumpToReaderAnchor() throws Exception {
        String fxml;
        try (var in = getClass().getResourceAsStream("/view/search-workspace.fxml")) {
            assertThat(in).isNotNull();
            fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(fxml).contains("annotationsSection", "annotationsListView", "annotationsCountLabel");

        String controller = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java"));
        assertThat(controller).contains(
                "AnnotationManagerService annotationManagerService",
                "annotationManagerService.query",
                "updateAnnotationResults",
                "workspaceManager.showAnnotationInReader",
                "KeyCode.ENTER",
                "ANNOTATION_RESULT_LIMIT");
        assertThat(controller).doesNotContain("SqliteAnnotationManagerQueryAdapter", "annotation_search_fts");
    }

    @Test
    void annotationSearchStringsAreLocalizedInBundledAndExternalCatalogs() throws Exception {
        for (String language : new String[]{"uk", "en", "bg"}) {
            String root = Files.readString(Path.of("../Lang/" + language + ".json"));
            String bundled = Files.readString(Path.of("src/main/resources/lang/default/" + language + ".json"));
            for (String key : new String[]{
                    "ui.search.annotations.title",
                    "ui.search.annotations.accessible",
                    "ui.search.annotations.found"}) {
                assertThat(root).contains("\"" + key + "\"");
                assertThat(bundled).contains("\"" + key + "\"");
            }
        }
    }
}
