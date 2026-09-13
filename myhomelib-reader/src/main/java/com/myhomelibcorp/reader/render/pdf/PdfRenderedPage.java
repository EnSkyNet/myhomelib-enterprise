package com.myhomelibcorp.reader.render.pdf;

/** Immutable ARGB page bitmap produced off the JavaFX thread. */
public record PdfRenderedPage(int pageIndex, int width, int height, int[] argb) {
    public PdfRenderedPage {
        if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must be >= 0");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid bitmap size");
        if (argb == null || argb.length != width * height) throw new IllegalArgumentException("invalid pixel buffer");
    }

    public long estimatedBytes() {
        return (long) argb.length * Integer.BYTES;
    }
}
