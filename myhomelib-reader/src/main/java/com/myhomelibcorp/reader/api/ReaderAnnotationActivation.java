package com.myhomelibcorp.reader.api;

/** Annotation activation emitted by the renderer/input layer. */
public record ReaderAnnotationActivation(
        ReaderAnnotationOverlay annotation,
        double screenX,
        double screenY
) {
    public ReaderAnnotationActivation {
        if (annotation == null) throw new IllegalArgumentException("annotation is required");
    }
}
