package com.myhomelibcorp.application.annotation.export;

public enum AnnotationExportFormat {
    MARKDOWN("md"),
    JSON("json"),
    HTML("html");

    private final String extension;

    AnnotationExportFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }
}
