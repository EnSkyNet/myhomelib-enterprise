package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents internal enum constants such as REMOTE/REGEX_REPLACE from leaking into user-facing controls. */
class UserVisibleEnumLocalizationContractTest {
    @Test
    void collectionPropertiesUsesLanguageCatalogForCollectionTypesAndButtons() throws Exception {
        String text = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/service/CollectionPropertiesUiService.java"));

        assertThat(text).contains("type.setConverter", "getLocalizationKey()", "common.save", "DialogLocalization.prepare");
        assertThat(text).doesNotContain("type.getItems().addAll(CollectionType.values())");
    }

    @Test
    void bulkMetadataEditorLocalizesFieldAndActionEnums() throws Exception {
        String text = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/metadata/BulkMetadataEditUiService.java"));

        assertThat(text).contains("field.setConverter", "action.setConverter", "this::fieldLabel", "this::actionLabel");
    }

    @Test
    void ukrainianCollectionTypeCatalogContainsNoEnglishOnlineLabel() throws Exception {
        String text = Files.readString(Path.of("../Lang/uk.json"));

        assertThat(text).contains("\"ui.collection.type.remote\": \"Онлайн FB2 / INPX\"");
        assertThat(text).contains("\"ui.collection.type.generic_remote\": \"Онлайн (будь-які формати)\"");
        assertThat(text).doesNotContain("\"ui.collection.type.remote\": \"Online");
    }
}
