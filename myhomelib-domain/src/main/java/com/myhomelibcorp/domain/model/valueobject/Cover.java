package com.myhomelibcorp.domain.model.valueobject;

import lombok.Value;

@Value
public class Cover {
    String id;
    String mimeType;
    int width;
    int height;
    byte[] data;

    public static Cover empty() {
        return new Cover(null, null, 0, 0, null);
    }

    public boolean isEmpty() {
        return data == null || data.length == 0;
    }
}