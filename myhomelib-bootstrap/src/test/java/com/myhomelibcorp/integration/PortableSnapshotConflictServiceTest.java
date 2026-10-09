package com.myhomelibcorp.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
    void exposesOnlyChangedUserSectionsAsLocalizationDescriptors() throws Exception {
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
        assertThat(conflicts).extracting(c -> c.entityType().key())
                .containsExactly("ui.sync.conflict.entity.reading_progress", "ui.sync.conflict.entity.filter_settings");
        assertThat(conflicts).allSatisfy(c -> {
            assertThat(c.reason().key()).isEqualTo("ui.sync.conflict.reason.portable_section_changed");
            assertThat(c.localSnapshot().key()).startsWith("ui.sync.conflict.snapshot.");
            assertThat(c.remoteSnapshot().key()).startsWith("ui.sync.conflict.snapshot.");
            assertThat(c.localSnapshot().arguments().getLast()).matches("[0-9a-f]{64}");
            assertThat(c.remoteSnapshot().arguments().getLast()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void scalarConflictPreviewDoesNotSplitSurrogatePairAtLimit() throws Exception {
        String localValue = "a".repeat(159) + "😀" + "tail";
        String remoteValue = "different";
        byte[] local = json("{\"schemaVersion\":4,\"format\":\"myhomelib-user-data\",\"custom\":"
                + mapper.writeValueAsString(localValue) + "}");
        byte[] remote = json("{\"schemaVersion\":4,\"format\":\"myhomelib-user-data\",\"custom\":"
                + mapper.writeValueAsString(remoteValue) + "}");

        String preview = (String) service.conflicts(local, remote).getFirst().localSnapshot().arguments().getFirst();

        assertThat(preview).endsWith("…");
        assertThat(Character.isHighSurrogate(preview.charAt(preview.length() - 2))).isFalse();
    }

    @Test
    void scalarConflictPreviewKeepsCombiningSequenceAtLimit() throws Exception {
        String localValue = "a".repeat(159) + "e\u0301" + "tail";
        byte[] local = json("{\"schemaVersion\":4,\"format\":\"myhomelib-user-data\",\"custom\":"
                + mapper.writeValueAsString(localValue) + "}");
        byte[] remote = json("{\"schemaVersion\":4,\"format\":\"myhomelib-user-data\",\"custom\":\"different\"}");

        String preview = (String) service.conflicts(local, remote).getFirst().localSnapshot().arguments().getFirst();

        assertThat(preview).endsWith("…");
        assertThat(preview).doesNotEndWith("e…");
        assertThat(preview).endsWith("a…");
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
        assertThat(result.path("bookmarks").get(0).path("id").asString()).isEqualTo("local");
        assertThat(result.path("filterSettings").path("lang").asString()).isEqualTo("en");
        assertThat(result.path("schemaVersion").asInt()).isEqualTo(4);
        assertThat(result.path("format").asString()).isEqualTo("myhomelib-user-data");
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
