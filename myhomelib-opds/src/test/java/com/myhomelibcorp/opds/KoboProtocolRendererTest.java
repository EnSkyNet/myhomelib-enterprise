package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.kobo.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KoboProtocolRendererTest {
    private final KoboProtocolRenderer renderer = new KoboProtocolRenderer();

    @Test
    void cursorTokenRoundTripsAndInvalidTokenFailsClosedToInitial() {
        String token = renderer.token(new KoboSyncCursor(42));
        assertThat(renderer.parseToken(token).value()).isEqualTo(42);
        assertThat(renderer.parseToken("not-a-valid-token").value()).isZero();
    }

    @Test
    void initializationPublishesOnlyDeviceScopedKoboEndpoints() {
        String json = renderer.initialization("https://library.example/kobo/device-token");
        assertThat(json).contains("/v1/library/sync", "/v1/auth/device", "/v1/library/{ProductId}/metadata");
        assertThat(json).doesNotContain("/opds/download/");
    }

    @Test
    void syncFixtureDistinguishesAddUpdateAndRemove() {
        KoboBookProjection book = book("11111111-1111-1111-1111-111111111111", "rev-1");
        KoboSyncBatch batch = new KoboSyncBatch(List.of(
                new KoboLibraryChange(1, KoboLibraryChange.Kind.ADD, book, book.bookId()),
                new KoboLibraryChange(2, KoboLibraryChange.Kind.UPDATE, book, book.bookId()),
                new KoboLibraryChange(3, KoboLibraryChange.Kind.REMOVE, null, book.bookId())
        ), new KoboSyncCursor(3), false);

        String json = renderer.sync(batch, "https://library.example/kobo/token");
        assertThat(json).contains("\"NewEntitlement\"", "\"ChangedEntitlement\"")
                .contains("\"IsRemoved\":true")
                .contains("/download/11111111-1111-1111-1111-111111111111/epub");
    }

    @Test
    void readingStateUsesFractionForKoboButRetainsMonotonicServerVersion() {
        KoboProgressState state = new KoboProgressState(
                "11111111-1111-1111-1111-111111111111", 37.5, "Reading", "chapter-4",
                Instant.parse("2026-10-02T08:00:00Z"), 9);
        String json = renderer.state(state);
        assertThat(json).contains("\"ProgressPercent\":0.375")
                .contains("\"MyHomeLibVersion\":9")
                .contains("\"Status\":\"Reading\"");
    }

    @Test
    void kepubArtifactIsAdvertisedAsKepub() {
        KoboBookProjection book = new KoboBookProjection(
                "11111111-1111-1111-1111-111111111111", "rev", "Title", "Author", "Series", 2,
                "artifact", "fp", "kepub", "book.kepub.epub", Instant.EPOCH);
        assertThat(renderer.metadata(book, "https://library.example/kobo/token"))
                .contains("\"Format\":\"KEPUB\"");
    }

    private static KoboBookProjection book(String id, String revision) {
        return new KoboBookProjection(id, revision, "Title", "Author", "Series", 1,
                "artifact", "fingerprint", "epub", "book.epub", Instant.parse("2026-10-01T00:00:00Z"));
    }
}
