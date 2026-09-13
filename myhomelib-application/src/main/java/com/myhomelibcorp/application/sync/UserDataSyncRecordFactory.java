package com.myhomelibcorp.application.sync;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Maps user-owned state to the format-neutral versioned sync model. */
public final class UserDataSyncRecordFactory {

    public SyncRecord readingProgress(ReadingProgressDto value, long baseVersion, long version,
                                      Instant changedAt, String deviceId) {
        Objects.requireNonNull(value, "value");
        String bookId = required(value.getBookId(), "bookId");
        Map<String, String> p = new LinkedHashMap<>();
        put(p, "anchorId", value.getAnchorId());
        p.put("paragraphIndex", Integer.toString(value.getParagraphIndex()));
        put(p, "paragraphId", value.getParagraphId());
        p.put("charOffset", Integer.toString(value.getCharOffset()));
        p.put("percent", Double.toString(value.getPercent()));
        put(p, "chapterTitle", value.getChapterTitle());
        put(p, "chapterId", value.getChapterId());
        if (value.getUpdatedAt() != null) p.put("updatedAt", value.getUpdatedAt().toString());
        p.put("readingTimeSeconds", Long.toString(value.getReadingTimeSeconds()));
        return live("progress:" + bookId, SyncEntityType.READING_PROGRESS, bookId,
                baseVersion, version, changedAt, deviceId, p);
    }

    public SyncRecord bookmark(Bookmark value, long baseVersion, long version,
                               Instant changedAt, String deviceId) {
        Objects.requireNonNull(value, "value");
        String id = required(value.getId(), "bookmark.id");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("bookId", required(value.getBookId(), "bookmark.bookId"));
        put(p, "paragraphId", value.getParagraphId());
        p.put("charOffset", Integer.toString(value.getCharOffset()));
        p.put("position", Double.toString(value.getPosition()));
        put(p, "chapterTitle", value.getChapterTitle());
        put(p, "context", value.getContext());
        if (value.getCreatedAt() != null) p.put("createdAt", value.getCreatedAt().toString());
        return live("bookmark:" + id, SyncEntityType.BOOKMARK, id,
                baseVersion, version, changedAt, deviceId, p);
    }

    public SyncRecord annotation(Annotation value, long baseVersion, long version,
                                 Instant changedAt, String deviceId) {
        Objects.requireNonNull(value, "value");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("type", value.type().name());
        p.put("bookId", value.anchor().bookId());
        put(p, "artifactId", value.anchor().artifactId());
        put(p, "chapterId", value.anchor().chapterId());
        put(p, "chapterTitle", value.anchor().chapterTitle());
        put(p, "paragraphId", value.anchor().paragraphId());
        p.put("startOffset", Long.toString(value.anchor().startOffset()));
        p.put("endOffset", Long.toString(value.anchor().endOffset()));
        p.put("position", Double.toString(value.anchor().position()));
        put(p, "quote", value.anchor().quote());
        put(p, "prefix", value.anchor().prefix());
        put(p, "suffix", value.anchor().suffix());
        p.put("color", value.color());
        p.put("note", value.note());
        p.put("tags", encodeTags(value.tags()));
        p.put("createdAt", value.createdAt().toString());
        p.put("updatedAt", value.updatedAt().toString());
        return live("annotation:" + value.id(), SyncEntityType.ANNOTATION, value.id(),
                baseVersion, version, changedAt, deviceId, p);
    }

    /** Groups need a sync id separate from the local AUTOINCREMENT row id. */
    public SyncRecord group(Group value, String stableSyncId, long baseVersion, long version,
                            Instant changedAt, String deviceId) {
        Objects.requireNonNull(value, "value");
        String localKey = value.getIdAsLong() == null ? required(value.getName(), "group.name")
                : Long.toString(value.getIdAsLong());
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", required(value.getName(), "group.name"));
        p.put("allowDelete", Boolean.toString(value.isAllowDelete()));
        return live(required(stableSyncId, "group stableSyncId"), SyncEntityType.GROUP, localKey,
                baseVersion, version, changedAt, deviceId, p);
    }

    public SyncRecord rating(String bookId, int rating, long baseVersion, long version,
                             Instant changedAt, String deviceId) {
        if (rating < 0 || rating > 5) throw new IllegalArgumentException("rating must be between 0 and 5");
        String key = required(bookId, "bookId");
        return live("rating:" + key, SyncEntityType.RATING, key, baseVersion, version, changedAt, deviceId,
                Map.of("rating", Integer.toString(rating)));
    }

    public SyncRecord favorite(String bookId, boolean favorite, long baseVersion, long version,
                               Instant changedAt, String deviceId) {
        String key = required(bookId, "bookId");
        return live("favorite:" + key, SyncEntityType.FAVORITE, key, baseVersion, version, changedAt, deviceId,
                Map.of("favorite", Boolean.toString(favorite)));
    }

    public SyncRecord setting(String key, String value, long baseVersion, long version,
                              Instant changedAt, String deviceId) {
        String normalizedKey = required(key, "setting key");
        return live("setting:" + normalizedKey, SyncEntityType.SETTING, normalizedKey,
                baseVersion, version, changedAt, deviceId, Map.of("value", value == null ? "" : value));
    }

    public SyncRecord tombstone(SyncEntityType type, String stableSyncId, String localKey,
                                long baseVersion, long version, Instant changedAt, String deviceId) {
        return SyncRecord.tombstone(required(stableSyncId, "stableSyncId"), Objects.requireNonNull(type, "type"),
                required(localKey, "localKey"), baseVersion, version,
                Objects.requireNonNull(changedAt, "changedAt"), required(deviceId, "deviceId"));
    }

    private static SyncRecord live(String id, SyncEntityType type, String localKey,
                                   long baseVersion, long version, Instant changedAt, String deviceId,
                                   Map<String, String> payload) {
        return SyncRecord.live(required(id, "syncId"), type, required(localKey, "localKey"), baseVersion, version,
                Objects.requireNonNull(changedAt, "changedAt"), required(deviceId, "deviceId"), payload);
    }

    private static void put(Map<String, String> target, String key, String value) {
        target.put(key, value == null ? "" : value);
    }

    private static String encodeTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        return String.join("\u001F", new TreeSet<>(tags));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
