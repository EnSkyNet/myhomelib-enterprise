package com.myhomelibcorp.reader.api;

public record ResourceInfo(
        String id,
        String mimeType,
        long offset,
        int length,
        boolean isImage
) {
    public static ResourceInfo image(String id, String mimeType, long offset, int length) {
        return new ResourceInfo(id, mimeType, offset, length, true);
    }

    public static ResourceInfo other(String id, String mimeType, long offset, int length) {
        return new ResourceInfo(id, mimeType, offset, length, false);
    }

    public boolean isEmpty() {
        return length == 0;
    }
}