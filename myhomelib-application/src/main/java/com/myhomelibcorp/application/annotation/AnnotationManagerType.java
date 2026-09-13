package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.domain.model.annotation.AnnotationType;

/** Application-facing annotation kind; keeps UI code independent of annotation domain types. */
public enum AnnotationManagerType {
    HIGHLIGHT,
    NOTE;

    static AnnotationManagerType fromDomain(AnnotationType type) {
        return type == AnnotationType.NOTE ? NOTE : HIGHLIGHT;
    }

    AnnotationType toDomain() {
        return this == NOTE ? AnnotationType.NOTE : AnnotationType.HIGHLIGHT;
    }
}
