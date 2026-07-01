package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;
import java.util.UUID;

public record SeriesId(UUID value) {
    public SeriesId {
        Objects.requireNonNull(value, "SeriesId cannot be null");
    }

    public static SeriesId generate() {
        return new SeriesId(UUID.randomUUID());
    }

    public static SeriesId fromString(String value) {
        return new SeriesId(UUID.fromString(value));
    }

    public String asString() {
        return value.toString();
    }
}