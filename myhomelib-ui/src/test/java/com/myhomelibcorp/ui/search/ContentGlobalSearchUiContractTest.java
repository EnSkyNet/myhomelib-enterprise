package com.myhomelibcorp.ui.search;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContentGlobalSearchUiContractTest {
    @Test
    void workspaceExposesMetadataContentsBothAndCancellableContentResults() throws Exception {
        String fxml;
        try (var in = getClass().getResourceAsStream("/view/search-workspace.fxml")) {
            assertThat(in).isNotNull();
            fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(fxml).contains("searchModeChoice", "contentSection", "contentListView", "contentCountLabel");

        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java"));
        assertThat(controller).contains(
                "METADATA(\"ui.search.mode.metadata\")",
                "CONTENTS(\"ui.search.mode.contents\")",
                "BOTH(\"ui.search.mode.both\")",
                "contentSearchService.search",
                "submitCancellable",
                "task.cancel(true)",
                "workspaceManager.showContentHitInReader",
                "KeyCode.ENTER");
    }

    @Test
    void readerContentHitUsesArtifactProjectionAndStableOffset() throws Exception {
        String workspace = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java"));
        String loader = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/service/FxmlLoaderFactory.java"));
        String reader = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
        assertThat(workspace).contains("showContentHitInReader", "loadNewReaderWorkspace(id, null, artifactId");
        assertThat(loader).contains("setContentSearchTarget(artifactId, contentOffset)");
        assertThat(reader).contains("selectPreferredArtifact(targetArtifact)", "jumpToContentSearchTarget", "readerView.goToPosition(position)");
    }

    @Test
    void contentSearchStringsExistInAllCatalogs() throws Exception {
        for (String language : new String[]{"uk", "en", "bg"}) {
            String root = Files.readString(Path.of("../Lang/" + language + ".json"));
            String bundled = Files.readString(Path.of("src/main/resources/lang/default/" + language + ".json"));
            for (String key : new String[]{"ui.search.mode.metadata", "ui.search.mode.contents", "ui.search.mode.both",
                    "ui.search.contents.title", "ui.search.contents.accessible", "ui.search.contents.status"}) {
                assertThat(root).contains("\"" + key + "\"");
                assertThat(bundled).contains("\"" + key + "\"");
            }
        }
    }
}
