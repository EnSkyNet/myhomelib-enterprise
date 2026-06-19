package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;
import java.util.UUID;

public record AuthorId(UUID value) {
    public AuthorId {
        Objects.requireNonNull(value, "AuthorId cannot be null");
    }

    public static AuthorId generate() {
        return new AuthorId(UUID.randomUUID());
    }

    public static AuthorId fromString(String value) {
        return new AuthorId(UUID.fromString(value));
    }

    public static AuthorId fromLong(long id) {
        return new AuthorId(UUID.nameUUIDFromBytes(Long.toString(id).getBytes()));
    }

    public String asString() {
        return value.toString();
    }
}