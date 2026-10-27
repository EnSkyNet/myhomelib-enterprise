package com.myhomelibcorp.ui.group;

import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Reviewed contract for the group context-menu export command. */
class GroupMetabibExportUiContractTest {
    @Test
    void groupWorkspaceControllerOffersAnExplicitMetabibExportAction() throws Exception {
        String source;
        try (var file = new FileInputStream(
                "src/main/java/com/myhomelibcorp/ui/group/GroupWorkspaceController.java")) {
            source = new String(file.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(source).contains(
                "MenuItem exportItem = new MenuItem(",
                "exportItem.setOnAction(",
                "exportGroupMetabib(group)",
                "submitCollectionScopedOperation(LibraryOperationType.EXPORT",
                "portableExporter.export(");
    }
}
