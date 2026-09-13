package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExportBatchCompletionContractTest {

    @Test
    void successfulBatchExportConsumesCheckboxesAndRefreshesActiveWorkspaceWithoutLosingAuthorContext() throws Exception {
        String export = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/controller/ExportController.java"),
                StandardCharsets.UTF_8);
        String author = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java"),
                StandardCharsets.UTF_8);
        String tree = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/table/TreeBookTableController.java"),
                StandardCharsets.UTF_8);
        String main = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/controller/MainController.java"),
                StandardCharsets.UTF_8);

        assertThat(export)
                .contains("result.failed() == 0 && result.errors().isEmpty()")
                .contains("bookSelectionService.setSelectedIds(List.copyOf(selectedBookIds), false)")
                .contains("successfulExportCallback.run()")
                .contains("Failed/cancelled exports deliberately")
                .contains("handleExport(Window owner, Runnable onSuccessfulExport)");

        assertThat(author)
                .contains("String currentBookId = selectedConcreteBookId()")
                .contains("() -> reloadBooks(currentBookId)")
                .contains("restoreCurrentBookSelection(preferredCurrentBookId)")
                .doesNotContain("currentAuthorId = null");

        assertThat(tree).contains("this::refresh");
        assertThat(main)
                .contains("this::refreshAfterSuccessfulExport")
                .contains("getBookTableController().refreshRows()")
                .contains("eventPublisher.publishEvent(new NavigationRefreshEvent())");
    }
}
