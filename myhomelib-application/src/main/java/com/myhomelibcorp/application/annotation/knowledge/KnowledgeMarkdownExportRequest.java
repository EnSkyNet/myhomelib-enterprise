package com.myhomelibcorp.application.annotation.knowledge;

import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;

import java.nio.file.Path;

public record KnowledgeMarkdownExportRequest(
        AnnotationExportSelection selection,
        Path rootDirectory,
        KnowledgeMarkdownExportTemplates templates,
        boolean includeFrontmatter,
        boolean includeBacklinks,
        KnowledgeMarkdownReExportPolicy reExportPolicy
) {
    public KnowledgeMarkdownExportRequest {
        if (selection == null) throw new IllegalArgumentException("selection is required");
        if (rootDirectory == null) throw new IllegalArgumentException("rootDirectory is required");
        rootDirectory = rootDirectory.toAbsolutePath().normalize();
        templates = templates == null ? KnowledgeMarkdownExportTemplates.defaults() : templates.normalized();
        reExportPolicy = reExportPolicy == null
                ? KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED : reExportPolicy;
    }
}
