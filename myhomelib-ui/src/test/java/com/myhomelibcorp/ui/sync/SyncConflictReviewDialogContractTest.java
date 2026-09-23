package com.myhomelibcorp.ui.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SyncConflictReviewDialogContractTest {
    @Test
    void manualReviewDialogShowsBothVersionsAndRequiresExplicitChoice() throws Exception {
        Path source = Path.of("src/main/java/com/myhomelibcorp/ui/sync/SyncConflictReviewDialog.java");
        String text = Files.readString(source);

        assertThat(text).contains("ui.text.local_version", "ui.text.remote_version", "ui.text.version_to_keep", "ui.sync.conflict.accessible_entity");
        assertThat(text).contains("render(i18n, row.item().entityType())", "render(i18n, row.item().reason())", "render(i18n, row.item().localSnapshot())");
        assertThat(text).contains("Side.LOCAL", "Side.REMOTE");
        assertThat(text).contains("ButtonBar.ButtonData.OK_DONE", "ButtonBar.ButtonData.CANCEL_CLOSE", "ui.text.cancel");
    }
}
