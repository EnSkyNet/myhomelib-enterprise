package com.myhomelibcorp.reader.render.comic;

/** Immutable ARGB raster prepared off the JavaFX thread. */
public record ComicRenderedPage(int pageIndex, int width, int height, int[] argb) {
    public ComicRenderedPage {
        if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must be non-negative");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid comic page size");
        if (argb == null || argb.length != width * height) throw new IllegalArgumentException("invalid ARGB buffer");
    }

    long estimatedBytes() {
        return (long) argb.length * Integer.BYTES;
    }
}
