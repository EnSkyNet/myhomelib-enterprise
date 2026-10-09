package com.myhomelibcorp.ui.annotation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationManagerReadwiseActionContractTest {
    @Test
    void readwiseExportUsesSelectedOrFilteredBookIdentityAndRemoteUseCase() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/annotation/AnnotationManagerWorkspaceController.java"));
        String fxml = Files.readString(Path.of("src/main/resources/view/annotation-manager-workspace.fxml"));
        assertThat(fxml).contains("onAction=\"#exportReadwise\"");
        assertThat(controller)
                .contains("public void exportReadwise()")
                .contains("map(AnnotationManagerItem::bookId)")
                .contains("filteredBook.id()")
                .contains("AnnotationExportSelection.books(selectedBookIds)")
                .contains("exportAnnotationsToReadwiseUseCase.export(selection)");
    }
}
