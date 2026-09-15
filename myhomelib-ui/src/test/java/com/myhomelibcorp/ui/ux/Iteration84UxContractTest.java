package com.myhomelibcorp.ui.ux;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Iteration84UxContractTest {

    @Test
    void mainToolbarKeepsFrequentActionsPersistentAndSelectionActionsContextual() throws Exception {
        String fxml = resource("/view/MainView.fxml");
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/controller/MainController.java"));

        assertThat(fxml).contains("fx:id=\"mainToolbar\"", "fx:id=\"selectionToolbar\"",
                "fx:id=\"activeScopeLabel\"", "visible=\"false\" managed=\"false\"",
                "#handleBatchRate", "#handleBatchProgress", "#handleBatchMetadata", "#handleClearSelection");
        assertThat(controller).contains("updateSelectionToolbar()", "selectionToolbar.setVisible(count > 0)",
                "updateActiveScope()");
    }

    @Test
    void mainMenuHasOneCanonicalLocationPerCommand() throws Exception {
        String fxml = resource("/view/MainView.fxml");
        int menuEnd = fxml.indexOf("</MenuBar>");
        assertThat(menuEnd).isPositive();
        String menu = fxml.substring(0, menuEnd);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("onAction=\"(#[A-Za-z0-9_]+)\"").matcher(menu);
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        while (matcher.find()) counts.merge(matcher.group(1), 1, Integer::sum);

        assertThat(counts).isNotEmpty();
        assertThat(counts.entrySet()).allMatch(entry -> entry.getValue() == 1,
                "each command must have one canonical MenuBar location; toolbar/context variants are allowed");
    }

    @Test
    void bookDetailsAreGroupedIntoFiveUserFacingSections() throws Exception {
        String fxml = resource("/view/details.fxml");

        assertThat(fxml).contains("<TitledPane text=\"ui.details.section.main\"",
                "<TitledPane text=\"ui.details.section.reading\"",
                "<TitledPane text=\"ui.details.section.files\"",
                "<TitledPane text=\"ui.details.section.library\"",
                "<TitledPane text=\"ui.details.section.technical\"");
    }

    @Test
    void searchWorkspaceSurfacesPinnedSmartCollectionsAsExplicitScope() throws Exception {
        String fxml = resource("/view/search-workspace.fxml");
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java"));

        assertThat(fxml).contains("pinnedScopesPane", "activeScopeLabel", "#clearSmartCollectionScope");
        assertThat(controller).contains("refreshPinnedScopes()", "updateActiveScopeIndicator()",
                "performSmartCollection(saved.getId(), saved.getName())", "scope-chip",
                "applyActiveSmartCollectionScope", "scoped ? GlobalSearchResult.empty()",
                "scope.includesContents() && activeSmartCollectionId == null",
                "Clearing the scope must not silently discard the user's refinement");
    }

    @Test
    void followedAuthorShowsStateAndNewBookCount() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java"));
        String fxml = resource("/view/author-workspace.fxml");

        assertThat(fxml).contains("followStatusLabel");
        assertThat(controller).contains("FollowedAuthorSummary::newBookCount",
                "ui.author.follow.following", "ui.author.follow.new_books", "ui.author.follow.up_to_date");
    }

    @Test
    void readerAnnotationSidebarSupportsTypeTagSearchAndChapterGrouping() throws Exception {
        String fxml = resource("/view/new-reader-workspace.fxml");
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));

        assertThat(fxml).contains("annotationSidebarSearch", "annotationSidebarTypeFilter", "annotationSidebarTagFilter");
        assertThat(controller).contains("buildAnnotationSidebarRows", "AnnotationSidebarRow.header",
                "annotationMatches(item, query)", "item.tags().contains(tag)");
    }

    private static String resource(String path) throws Exception {
        try (var in = Iteration84UxContractTest.class.getResourceAsStream(path)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
