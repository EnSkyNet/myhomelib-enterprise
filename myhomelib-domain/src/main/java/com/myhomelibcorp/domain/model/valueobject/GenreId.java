package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;

public record GenreId(String code) {
    public GenreId {
        Objects.requireNonNull(code, "GenreId cannot be null");
        if (code.isBlank()) {
            throw new IllegalArgumentException("GenreId cannot be blank");
        }
    }

    public static GenreId fromCode(String code) {
        return new GenreId(code);
    }

    public String asString() {
        return code;
    }

    @Override
    public String toString() {
        return code;
    }
}