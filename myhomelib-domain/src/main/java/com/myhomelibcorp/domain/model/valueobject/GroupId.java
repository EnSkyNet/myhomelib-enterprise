package com.myhomelibcorp.domain.model.valueobject;

import java.util.Objects;

public record GroupId(Long value) {
    public GroupId {
        Objects.requireNonNull(value, "GroupId cannot be null");
    }

    public static GroupId fromLong(Long value) {
        return new GroupId(value);
    }

    public Long asLong() {
        return value;
    }
}