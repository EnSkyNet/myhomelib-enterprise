package com.myhomelibcorp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortableSnapshotConflictServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PortableSnapshotConflictService service = new PortableSnapshotConflictService(mapper);

    @Test
    void exposesOnlyChangedUserSectionsAndUsesUkrainianLabels() throws Exception {
        byte[] local = json("""
                {"schemaVersion":4,"format":"myhomelib-user-data",
                 "readingProgress":[{"bookId":"a","percent":10}],
                 "bookmarks":[{"id":"b1"}],"filterSettings":{"lang":"uk"}}
                """);
        byte[] remote = json("""
                {"schemaVersion":4,"format":"myhomelib-user-data",
                 "readingProgress":[{"bookId":"a","percent":70}],
                 "bookmarks":[{"id":"b1"}],"filterSettings":{"lang":"en"}}
                """);

        var conflicts = service.conflicts(local, remote);

        assertThat(conflicts).extracting(c -> c.logicalKey())
                .containsExactly("portable-user-data:readingProgress", "portable-user-data:filterSettings");
        assertThat(conflicts).extracting(c -> c.entityType())
                .containsExactly("Прогрес читання", "Налаштування фільтрів");
        assertThat(conflicts).allSatisfy(c -> {
            assertThat(c.localSnapshot()).contains("SHA-256:");
            assertThat(c.remoteSnapshot()).contains("SHA-256:");
        });
    }

    @Test
    void mergesEachConflictingSectionAccordingToExplicitChoice() throws Exception {
        byte[] local = json("""
                {"schemaVersion":4,"format":"myhomelib-user-data",
                 "readingProgress":[{"bookId":"a","percent":10}],
                 "bookmarks":[{"id":"local"}],"filterSettings":{"lang":"uk"}}
                """);
        byte[] remote = json("""
                {"schemaVersion":4,"format":"myhomelib-user-data",
                 "readingProgress":[{"bookId":"a","percent":70}],
                 "bookmarks":[{"id":"remote"}],"filterSettings":{"lang":"en"}}
                """);

        byte[] merged = service.merge(local, remote, List.of(
                new SyncConflictReviewSelection("portable-user-data:readingProgress", SyncConflictReviewSelection.Side.REMOTE),
                new SyncConflictReviewSelection("portable-user-data:bookmarks", SyncConflictReviewSelection.Side.LOCAL),
                new SyncConflictReviewSelection("portable-user-data:filterSettings", SyncConflictReviewSelection.Side.REMOTE)
        ));
        JsonNode result = mapper.readTree(merged);

        assertThat(result.path("readingProgress").get(0).path("percent").asInt()).isEqualTo(70);
        assertThat(result.path("bookmarks").get(0).path("id").asText()).isEqualTo("local");
        assertThat(result.path("filterSettings").path("lang").asText()).isEqualTo("en");
        assertThat(result.path("schemaVersion").asInt()).isEqualTo(4);
        assertThat(result.path("format").asText()).isEqualTo("myhomelib-user-data");
    }

    @Test
    void refusesPartialOrStaleSelectionSet() throws Exception {
        byte[] local = json("""
                {"schemaVersion":4,"format":"myhomelib-user-data","bookmarks":[{"id":"a"}],"groups":[]}
                """);
        byte[] remote = json("""
                {"schemaVersion":4,"format":"myhomelib-user-data","bookmarks":[{"id":"b"}],"groups":[{"id":"g"}]}
                """);

        assertThatThrownBy(() -> service.merge(local, remote, List.of(
                new SyncConflictReviewSelection("portable-user-data:bookmarks", SyncConflictReviewSelection.Side.LOCAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("кожного конфліктного розділу");
    }

    @Test
    void rejectsIncompatibleSnapshotSchemaBeforeUserCanMerge() throws Exception {
        byte[] local = json("{" + "\"schemaVersion\":4,\"format\":\"myhomelib-user-data\",\"bookmarks\":[]}");
        byte[] remote = json("{" + "\"schemaVersion\":5,\"format\":\"myhomelib-user-data\",\"bookmarks\":[]}");

        assertThatThrownBy(() -> service.conflicts(local, remote))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("несумісні");
    }

    private static byte[] json(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
