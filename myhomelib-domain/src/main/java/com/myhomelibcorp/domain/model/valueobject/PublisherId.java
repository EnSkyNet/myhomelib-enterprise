package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;
import java.util.UUID;

public record PublisherId(UUID value) {
    public PublisherId {
        Objects.requireNonNull(value, "PublisherId cannot be null");
    }

    public static PublisherId generate() {
        return new PublisherId(UUID.randomUUID());
    }

    public static PublisherId fromString(String value) {
        return new PublisherId(UUID.fromString(value));
    }

    public String asString() {
        return value.toString();
    }

    @Override
    public String toString() {
        return asString();
    }
}