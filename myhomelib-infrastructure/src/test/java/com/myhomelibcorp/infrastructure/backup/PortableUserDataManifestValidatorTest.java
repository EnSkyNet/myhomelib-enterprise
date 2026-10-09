package com.myhomelibcorp.infrastructure.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortableUserDataManifestValidatorTest {
    @TempDir Path tempDir;

    private final PortableUserDataManifestValidator validator =
            new PortableUserDataManifestValidator(new ObjectMapper());

    @Test
    void validatesCurrentV4ManifestWithoutMaterializingSectionPayloads() throws Exception {
        Path manifest = tempDir.resolve("valid-v4.json");
        Files.writeString(manifest, currentV4(
                "\"readerSettings\":{\"global\":null,\"perBook\":[]}"));

        PortableUserDataManifestValidator.ManifestHeader header = validator.inspect(manifest, 4);

        assertThat(header.sourceVersion()).isEqualTo(4);
        assertThat(header.format()).isEqualTo("myhomelib-user-data");
        assertThatCode(() -> validator.validateCurrentStructure(manifest, header.sourceVersion()))
                .doesNotThrowAnyException();
    }

    @Test
    void validatesCurrentV5ManifestOnlyWhenReadingListSectionsArePresent() throws Exception {
        Path manifest = tempDir.resolve("valid-v5.json");
        Files.writeString(manifest, currentV5("\"readerSettings\":{\"global\":null,\"perBook\":[]}"));

        PortableUserDataManifestValidator.ManifestHeader header = validator.inspect(manifest, 5);

        assertThat(header.sourceVersion()).isEqualTo(5);
        assertThatCode(() -> validator.validateCurrentStructure(manifest, header.sourceVersion()))
                .doesNotThrowAnyException();

        Path missing = tempDir.resolve("missing-reading-lists-v5.json");
        Files.writeString(missing, currentV5("\"readerSettings\":{\"global\":null,\"perBook\":[]}")
                .replace("\"readingLists\":[],\"readingListItems\":[],", ""));
        assertThatThrownBy(() -> validator.validateCurrentStructure(missing, 5))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readingLists");
    }


    @Test
    void validatesCurrentV6ManifestOnlyWhenScopedReaderSectionIsPresent() throws Exception {
        Path manifest = tempDir.resolve("valid-v6.json");
        Files.writeString(manifest, currentV6("\"readerSettings\":{\"global\":null,\"perBook\":[],\"scoped\":[]}"));

        PortableUserDataManifestValidator.ManifestHeader header = validator.inspect(manifest, 6);
        assertThat(header.sourceVersion()).isEqualTo(6);
        assertThatCode(() -> validator.validateCurrentStructure(manifest, header.sourceVersion()))
                .doesNotThrowAnyException();

        Path missing = tempDir.resolve("missing-scoped-v6.json");
        Files.writeString(missing, currentV6("\"readerSettings\":{\"global\":null,\"perBook\":[]}"));
        assertThatThrownBy(() -> validator.validateCurrentStructure(missing, 6))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readerSettings.scoped");
    }

    @Test
    void rejectsConflictingHeaderBeforeCurrentSchemaValidation() throws Exception {
        Path manifest = tempDir.resolve("conflicting.json");
        Files.writeString(manifest, currentV4(
                "\"readerSettings\":{\"global\":null,\"perBook\":[]}")
                .replace("\"schemaVersion\":4", "\"schemaVersion\":4,\"version\":3"));

        assertThatThrownBy(() -> validator.inspect(manifest, 4))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Conflicting portable user-data schema versions");
    }

    @Test
    void rejectsMalformedReaderSettingsShapeDuringStreamingPreflight() throws Exception {
        Path manifest = tempDir.resolve("bad-reader-settings.json");
        Files.writeString(manifest, currentV4(
                "\"readerSettings\":{\"global\":[],\"perBook\":[]}"));

        assertThatThrownBy(() -> validator.validateCurrentStructure(manifest, 4))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readerSettings.global must be an object or null");
    }


    private static String currentV6(String readerSettings) {
        return currentV5(readerSettings)
                .replace("\"schemaVersion\":5", "\"schemaVersion\":6");
    }

    private static String currentV5(String readerSettings) {
        return currentV4(readerSettings)
                .replace("\"schemaVersion\":4", "\"schemaVersion\":5")
                .replace("\"filterSettings\":{}", "\"readingLists\":[],\"readingListItems\":[],\"filterSettings\":{}");
    }

    private static String currentV4(String readerSettings) {
        return "{"
                + "\"schemaVersion\":4,"
                + "\"format\":\"myhomelib-user-data\","
                + "\"bookState\":[],\"readingProgress\":[],\"readingHistory\":[],"
                + "\"readingStats\":[],\"bookmarks\":[],\"groups\":[],"
                + "\"groupMemberships\":[],\"savedSearches\":[],"
                + "\"customFieldDefinitions\":[],\"customFieldValues\":[],"
                + "\"annotations\":[],\"annotationTags\":[],"
                + "\"filterSettings\":{}," + readerSettings
                + "}";
    }
}
