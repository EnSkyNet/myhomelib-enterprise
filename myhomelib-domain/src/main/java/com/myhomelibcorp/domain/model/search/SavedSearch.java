package com.myhomelibcorp.domain.model.search;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
public class SavedSearch {
    private final String id;
    private final String name;
    private final String query;
    private final String filters;
    private final LocalDateTime createdAt;
    private LocalDateTime lastUsed;
    private int useCount;
    private final SavedSearchKind kind;
    private final boolean pinned;
    private final SmartCollectionSpec smartCollection;

    /** Backward-compatible constructor for classic saved searches. */
    public SavedSearch(String name, String query, String filters) {
        this(UUID.randomUUID().toString(), requireName(name), requireQuery(query), filters,
                LocalDateTime.now(), LocalDateTime.now(), 0,
                SavedSearchKind.SEARCH, false, null);
    }

    private SavedSearch(String id, String name, String query, String filters,
                        LocalDateTime createdAt, LocalDateTime lastUsed, int useCount,
                        SavedSearchKind kind, boolean pinned, SmartCollectionSpec smartCollection) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = requireName(name);
        this.kind = kind == null ? SavedSearchKind.SEARCH : kind;
        this.query = this.kind == SavedSearchKind.SEARCH ? requireQuery(query) : (query == null ? "" : query);
        this.filters = filters;
        this.createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        this.lastUsed = lastUsed == null ? this.createdAt : lastUsed;
        this.useCount = Math.max(0, useCount);
        this.pinned = pinned;
        this.smartCollection = smartCollection;
        if (this.kind == SavedSearchKind.SMART_COLLECTION && smartCollection == null) {
            throw new IllegalArgumentException("Smart collection definition is required");
        }
        if (this.kind == SavedSearchKind.SEARCH && smartCollection != null) {
            throw new IllegalArgumentException("Classic saved search cannot contain smart collection definition");
        }
    }

    public static SavedSearch smartCollection(String name, SmartCollectionSpec spec, boolean pinned) {
        return new SavedSearch(UUID.randomUUID().toString(), requireName(name), "", null,
                LocalDateTime.now(), LocalDateTime.now(), 0,
                SavedSearchKind.SMART_COLLECTION, pinned, Objects.requireNonNull(spec, "spec"));
    }

    /** Persistence reconstruction without reflection. */
    public static SavedSearch restore(String id, String name, String query, String filters,
                                      LocalDateTime createdAt, LocalDateTime lastUsed, int useCount,
                                      SavedSearchKind kind, boolean pinned, SmartCollectionSpec smartCollection) {
        return new SavedSearch(id, name, query, filters, createdAt, lastUsed, useCount,
                kind, pinned, smartCollection);
    }

    public boolean isSmartCollection() {
        return kind == SavedSearchKind.SMART_COLLECTION;
    }

    public SavedSearch withName(String newName) {
        return new SavedSearch(id, newName, query, filters, createdAt, lastUsed, useCount,
                kind, pinned, smartCollection);
    }

    public SavedSearch withPinned(boolean newPinned) {
        return new SavedSearch(id, name, query, filters, createdAt, lastUsed, useCount,
                kind, newPinned, smartCollection);
    }

    public SavedSearch withSmartCollection(SmartCollectionSpec spec) {
        if (!isSmartCollection()) throw new IllegalStateException("Not a smart collection");
        return new SavedSearch(id, name, "", filters, createdAt, lastUsed, useCount,
                SavedSearchKind.SMART_COLLECTION, pinned, Objects.requireNonNull(spec, "spec"));
    }

    public SavedSearch withUsage() {
        this.lastUsed = LocalDateTime.now();
        this.useCount++;
        return this;
    }

    @Override
    public String toString() {
        return (pinned ? "★ " : "") + name + " (" + useCount + ")";
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Saved search name cannot be blank");
        return value.trim();
    }

    private static String requireQuery(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Saved search query cannot be blank");
        return value.trim();
    }
}
