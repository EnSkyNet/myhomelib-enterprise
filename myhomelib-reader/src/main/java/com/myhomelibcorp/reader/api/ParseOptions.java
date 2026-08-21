package com.myhomelibcorp.reader.api;

public record ParseOptions(
        boolean loadImages,
        boolean loadFootnotes,
        boolean buildToc,
        int maxImageSizeBytes,
        String preferredEncoding
) {
    public static ParseOptions defaultOptions() {
        return new ParseOptions(
                true,
                true,
                true,
                10 * 1024 * 1024,
                null
        );
    }

    public static ParseOptions minimal() {
        return new ParseOptions(
                false,
                false,
                true,
                0,
                null
        );
    }

    public static ParseOptions withoutImages() {
        return new ParseOptions(
                false,
                true,
                true,
                0,
                null
        );
    }
}