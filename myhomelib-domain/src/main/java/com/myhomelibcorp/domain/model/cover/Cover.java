package com.myhomelibcorp.domain.model.cover;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

@Getter
@AllArgsConstructor
@ToString
public class Cover {

    private final String id;
    private final String mimeType;
    private final byte[] data;
    private final int width;
    private final int height;

    public boolean isEmpty() {
        return data == null || data.length == 0;
    }

    public static Cover empty() {
        return new Cover(null, null, null, 0, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cover cover)) return false;
        return Objects.equals(id, cover.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}