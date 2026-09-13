package com.myhomelibcorp.application.annotation.export;

import java.nio.file.Path;

public record AnnotationExportRequest(
        AnnotationExportFormat format,
        AnnotationExportSelection selection,
        Path destination,
        AnnotationExportTemplates templates
) {
    public AnnotationExportRequest {
        if (format == null) throw new IllegalArgumentException("format is required");
        if (selection == null) throw new IllegalArgumentException("selection is required");
        if (destination == null) throw new IllegalArgumentException("destination is required");
        destination = destination.toAbsolutePath().normalize();
        templates = templates == null ? AnnotationExportTemplates.defaults() : templates.normalized();
    }
}
