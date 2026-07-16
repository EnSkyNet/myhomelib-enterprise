package com.myhomelibcorp.domain.model.valueobject;

public record GroupId(Long value) {
    public GroupId {
        // Дозволяємо null (для нових записів)
    }

    public static GroupId fromLong(Long value) {
        return new GroupId(value);
    }

    public Long asLong() {
        return value;
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : "null";
    }
}