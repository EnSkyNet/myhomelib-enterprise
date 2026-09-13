package com.myhomelibcorp.application.annotation.export;

import java.nio.file.Path;

public record AnnotationExportResult(long annotationCount, Path destination) { }
