package com.myhomelibcorp.application.sync;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;
import com.myhomelibcorp.domain.model.annotation.AnnotationType;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDataSyncRecordFactoryTest {
    private final UserDataSyncRecordFactory factory = new UserDataSyncRecordFactory();
    private final Instant now = Instant.parse("2026-09-12T12:00:00Z");

    @Test
    void versionsProgressBookmarkAndAnnotationWithStableIds() {
        ReadingProgressDto progress = ReadingProgressDto.builder()
                .bookId("book-1").anchorId("a-1").paragraphIndex(3).paragraphId("p-3")
                .charOffset(17).percent(0.42).chapterTitle("Chapter").chapterId("ch-1")
                .updatedAt(LocalDateTime.of(2026, 9, 12, 15, 0)).readingTimeSeconds(90).build();
        Bookmark bookmark = Bookmark.builder().id("bm-1").bookId("book-1").paragraphId("p-3")
                .charOffset(17).position(0.42).chapterTitle("Chapter").context("text")
                .createdAt(LocalDateTime.of(2026, 9, 12, 15, 1)).build();
        Annotation annotation = new Annotation("ann-1", AnnotationType.NOTE,
                new AnnotationAnchor("book-1", "artifact-1", "ch-1", "Chapter", "p-3",
                        10, 20, 0.42, "quote", "pre", "post"), "#FFF59D", "note",
                Set.of("tag-b", "tag-a"), now.minusSeconds(10), now);

        SyncRecord p = factory.readingProgress(progress, 2, 3, now, "device-a");
        SyncRecord b = factory.bookmark(bookmark, 0, 1, now, "device-a");
        SyncRecord a = factory.annotation(annotation, 4, 5, now, "device-a");

        assertThat(p.syncId()).isEqualTo("progress:book-1");
        assertThat(p.entityType()).isEqualTo(SyncEntityType.READING_PROGRESS);
        assertThat(p.payload()).containsEntry("paragraphId", "p-3").containsEntry("readingTimeSeconds", "90");
        assertThat(b.syncId()).isEqualTo("bookmark:bm-1");
        assertThat(a.syncId()).isEqualTo("annotation:ann-1");
        assertThat(a.payload()).containsEntry("position", "0.42").containsEntry("tags", "tag-a\u001Ftag-b");
    }

    @Test
    void coversRatingFavoriteGroupSettingAndDeleteTombstone() {
        SyncRecord rating = factory.rating("book-2", 5, 0, 1, now, "device-a");
        SyncRecord favorite = factory.favorite("book-2", true, 1, 2, now, "device-a");
        SyncRecord group = factory.group(new Group("Favorites", true), "group:stable-1", 0, 1, now, "device-a");
        SyncRecord setting = factory.setting("reader.theme", "dark", 7, 8, now, "device-a");
        SyncRecord deleted = factory.tombstone(SyncEntityType.ANNOTATION, "annotation:ann-9", "ann-9",
                8, 9, now, "device-a");

        assertThat(rating.payload()).containsEntry("rating", "5");
        assertThat(favorite.payload()).containsEntry("favorite", "true");
        assertThat(group.syncId()).isEqualTo("group:stable-1");
        assertThat(group.payload()).containsEntry("name", "Favorites");
        assertThat(setting.payload()).containsEntry("value", "dark");
        assertThat(deleted.tombstone()).isTrue();
        assertThat(deleted.payload()).isEmpty();
        assertThatThrownBy(() -> factory.rating("book", 6, 0, 1, now, "device-a"))
                .hasMessageContaining("between 0 and 5");
    }
}
