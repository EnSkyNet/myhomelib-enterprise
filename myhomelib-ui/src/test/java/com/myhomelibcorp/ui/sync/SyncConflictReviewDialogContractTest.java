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

        assertThat(text).contains("Локальна версія", "Віддалена версія", "Яку версію зберегти");
        assertThat(text).contains("Side.LOCAL", "Side.REMOTE");
        assertThat(text).contains("ButtonBar.ButtonData.OK_DONE", "ButtonType.CANCEL");
    }
}
