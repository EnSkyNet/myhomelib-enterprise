package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;
import java.util.UUID;

public record BookId(UUID value) {
    public BookId {
        Objects.requireNonNull(value, "BookId cannot be null");
    }

    public static BookId generate() {
        return new BookId(UUID.randomUUID());
    }

    public static BookId fromString(String value) {
        return new BookId(UUID.fromString(value));
    }

    public static BookId fromLong(long id) {
        // Для зворотної сумісності з SQLite
        return new BookId(UUID.nameUUIDFromBytes(Long.toString(id).getBytes()));
    }

    public String asString() {
        return value.toString();
    }

    @Override
    public String toString() {
        return asString();
    }
}