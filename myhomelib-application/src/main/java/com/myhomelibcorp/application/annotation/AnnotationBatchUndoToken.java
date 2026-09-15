package com.myhomelibcorp.application.annotation;

import java.util.List;

/** One undoable manager operation, possibly containing several annotations. */
public record AnnotationBatchUndoToken(List<AnnotationUndoToken> annotations) {
    public AnnotationBatchUndoToken {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        if (annotations.isEmpty()) throw new IllegalArgumentException("annotations are required");
    }

    public int size() {
        return annotations.size();
    }
}
