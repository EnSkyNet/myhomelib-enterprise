package com.myhomelibcorp.domain.model.readinglist;

import java.util.Objects;
import java.util.UUID;

public record ReadingListId(String value) {
    public ReadingListId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Reading list id cannot be blank");
    }

    public static ReadingListId newId() { return new ReadingListId(UUID.randomUUID().toString()); }
    public static ReadingListId fromString(String value) { return new ReadingListId(value); }
    @Override public String toString() { return value; }
}
