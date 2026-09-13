package com.myhomelibcorp.application.port.out.annotation;

import com.myhomelibcorp.application.annotation.AnnotationManagerFacets;
import com.myhomelibcorp.application.annotation.AnnotationManagerFilter;
import com.myhomelibcorp.application.annotation.AnnotationManagerPage;

/** Storage-side paged query for Annotation Manager. */
public interface AnnotationManagerQueryPort {
    AnnotationManagerPage query(AnnotationManagerFilter filter, int offset, int limit);
    AnnotationManagerFacets facets();
}
