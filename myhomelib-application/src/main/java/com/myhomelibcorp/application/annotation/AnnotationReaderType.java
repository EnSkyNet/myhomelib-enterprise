package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.domain.model.annotation.AnnotationType;

/** Renderer-neutral annotation kind exposed by the application layer. */
public enum AnnotationReaderType {
    HIGHLIGHT,
    NOTE;

    static AnnotationReaderType fromDomain(AnnotationType type) {
        return type == AnnotationType.NOTE ? NOTE : HIGHLIGHT;
    }
}
