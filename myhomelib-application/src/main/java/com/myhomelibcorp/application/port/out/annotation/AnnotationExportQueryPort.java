package com.myhomelibcorp.application.port.out.annotation;

import com.myhomelibcorp.application.annotation.export.AnnotationExportPage;
import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;

/**
 * Bounded, deterministic storage-side projection for rich annotation export.
 * Pages form one stable total order and rows for the same logical book are contiguous;
 * integrations may therefore stream one output document per book without loading all rows.
 */
public interface AnnotationExportQueryPort {
    AnnotationExportPage query(AnnotationExportSelection selection, int offset, int limit);
}
