package com.myhomelibcorp.application.annotation.knowledge;

import java.nio.file.Path;

public record KnowledgeMarkdownExportResult(
        long writtenBooks,
        long skippedBooks,
        long annotationCount,
        Path rootDirectory
) { }
