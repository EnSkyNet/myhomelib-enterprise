package com.myhomelibcorp.reader.render.api;

import java.util.Objects;

/** Shared contract for an off-FX-thread rendered ARGB page. */
public interface ArgbRenderedPage {
    int pageIndex();

    int width();

    int height();

    int[] argb();

    default long estimatedBytes() {
        return (long) argb().length * Integer.BYTES;
    }

    static void validate(int pageIndex, int width, int height, int[] argb) {
        if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must be >= 0");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid bitmap size");
        Objects.requireNonNull(argb, "argb");
        long expectedPixels = (long) width * height;
        if (expectedPixels > Integer.MAX_VALUE || argb.length != (int) expectedPixels) {
            throw new IllegalArgumentException("invalid ARGB pixel buffer");
        }
    }
}
