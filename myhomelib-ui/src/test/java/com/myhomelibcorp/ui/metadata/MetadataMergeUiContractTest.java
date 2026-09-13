package com.myhomelibcorp.ui.metadata;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataMergeUiContractTest {
    @Test
    void reviewRequiresExplicitCheckboxSelectionAndSupportsBatchInput() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/metadata/MetadataMergeUiService.java"), StandardCharsets.UTF_8);
        assertThat(source).contains(
                "review(Window owner, List<MetadataMergePreview> source)",
                "check.setSelected(false)",
                "button.setDisable(selected.values().stream().allMatch(Set::isEmpty))",
                "Пакетний перегляд онлайн-метаданих");
    }

    @Test
    void classicEditExposesOnlineMetadataEntryPoint() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/service/ClassicLibraryActionsService.java"), StandardCharsets.UTF_8);
        assertThat(source).contains("Онлайн-метадані…", "metadataMergeUi.lookupAndReview(owner, book.getId(), onSuccess)");
    }
}
