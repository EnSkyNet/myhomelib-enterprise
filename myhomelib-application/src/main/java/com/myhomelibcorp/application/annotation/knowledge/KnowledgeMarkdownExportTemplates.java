package com.myhomelibcorp.application.annotation.knowledge;

/**
 * Templates used by the Obsidian/Joplin-compatible Markdown export.
 *
 * <p>Folder/file templates support: ${bookId}, ${bookTitle}, ${authors}, ${series},
 * ${language}, ${fileName}, ${isbn}. Annotation templates additionally support:
 * ${id}, ${type}, ${color}, ${chapterId}, ${chapter}, ${quote}, ${note}, ${tags},
 * ${position}, ${createdAt}, ${updatedAt}, ${backlink}.</p>
 */
public record KnowledgeMarkdownExportTemplates(
        String folderTemplate,
        String fileNameTemplate,
        String annotationTemplate
) {
    public static KnowledgeMarkdownExportTemplates defaults() {
        return new KnowledgeMarkdownExportTemplates(
                "${authors}",
                "${bookTitle} [${bookId}]",
                "## ${chapter}\n\n" +
                        "> ${quote}\n\n" +
                        "${note}\n\n" +
                        "- **Type:** ${type}\n" +
                        "- **Tags:** ${tags}\n" +
                        "- **Position:** ${position}\n" +
                        "- **Updated:** ${updatedAt}\n" +
                        "${backlink}\n\n"
        );
    }

    public KnowledgeMarkdownExportTemplates normalized() {
        KnowledgeMarkdownExportTemplates defaults = defaults();
        return new KnowledgeMarkdownExportTemplates(
                nonBlank(folderTemplate, defaults.folderTemplate),
                nonBlank(fileNameTemplate, defaults.fileNameTemplate),
                nonBlank(annotationTemplate, defaults.annotationTemplate));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
