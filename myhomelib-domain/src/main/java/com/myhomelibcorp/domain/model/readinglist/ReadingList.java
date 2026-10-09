package com.myhomelibcorp.domain.model.readinglist;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ReadingList(ReadingListId id, String name, String description,
                          LocalDateTime createdAt, LocalDateTime updatedAt,
                          List<ReadingListItem> items) {
    public ReadingList {
        Objects.requireNonNull(id, "id");
        name = normalizeName(name);
        description = description == null ? "" : description.trim();
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        items = items == null ? List.of() : List.copyOf(items);
        Set<BookId> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ReadingListItem item = Objects.requireNonNull(items.get(i), "item");
            if (item.position() != i) throw new IllegalArgumentException("Reading-list positions must be contiguous 0..N-1");
            if (!seen.add(item.bookId())) throw new IllegalArgumentException("Duplicate book in reading list: " + item.bookId());
        }
    }

    public static ReadingList create(String name, String description) {
        LocalDateTime now = LocalDateTime.now();
        return new ReadingList(ReadingListId.newId(), name, description, now, now, List.of());
    }

    public ReadingList rename(String newName) {
        return new ReadingList(id, newName, description, createdAt, LocalDateTime.now(), items);
    }

    public ReadingList withDescription(String value) {
        return new ReadingList(id, name, value, createdAt, LocalDateTime.now(), items);
    }

    private static String normalizeName(String value) {
        String normalized = Objects.requireNonNull(value, "name").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Reading list name cannot be blank");
        if (normalized.length() > 200) throw new IllegalArgumentException("Reading list name is too long");
        return normalized;
    }
}
