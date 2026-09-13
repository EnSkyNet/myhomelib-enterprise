package com.myhomelibcorp.application.annotation.export;

/**
 * User-editable templates for human-readable annotation exports.
 * ${items} is the only raw document placeholder; every row placeholder is escaped for its format.
 */
public record AnnotationExportTemplates(
        String markdownDocument,
        String markdownItem,
        String htmlDocument,
        String htmlItem
) {
    public static AnnotationExportTemplates defaults() {
        return new AnnotationExportTemplates(
                "# MyHomeLib annotations\n\n${items}",
                "## ${bookTitle}\n\n" +
                        "- **Annotation ID:** `${id}`\n" +
                        "- **Book ID:** `${bookId}`\n" +
                        "- **Authors:** ${authors}\n" +
                        "- **Series:** ${series}\n" +
                        "- **Language:** ${language}\n" +
                        "- **File:** ${fileName}\n" +
                        "- **ISBN:** ${isbn}\n" +
                        "- **Type:** ${type}\n" +
                        "- **Color:** ${color}\n" +
                        "- **Chapter:** ${chapter}\n" +
                        "- **Position:** ${position}\n" +
                        "- **Tags:** ${tags}\n" +
                        "- **Created:** ${createdAt}\n" +
                        "- **Updated:** ${updatedAt}\n\n" +
                        "> ${quote}\n\n" +
                        "${note}\n\n",
                "<!doctype html><html><head><meta charset=\"UTF-8\"><title>MyHomeLib annotations</title>" +
                        "<style>body{font-family:system-ui,sans-serif;max-width:960px;margin:2rem auto;padding:0 1rem}" +
                        "article{border-bottom:1px solid #ddd;padding:1rem 0}blockquote{white-space:pre-wrap}" +
                        ".meta{color:#555}pre{white-space:pre-wrap}</style></head><body><h1>MyHomeLib annotations</h1>${items}</body></html>",
                "<article data-annotation-id=\"${id}\"><h2>${bookTitle}</h2>" +
                        "<div class=\"meta\"><div><strong>Annotation ID:</strong> ${id}</div>" +
                        "<div><strong>Book ID:</strong> ${bookId}</div><div><strong>Authors:</strong> ${authors}</div>" +
                        "<div><strong>Series:</strong> ${series}</div><div><strong>Language:</strong> ${language}</div>" +
                        "<div><strong>File:</strong> ${fileName}</div><div><strong>ISBN:</strong> ${isbn}</div>" +
                        "<div><strong>Type:</strong> ${type}</div><div><strong>Color:</strong> ${color}</div>" +
                        "<div><strong>Chapter:</strong> ${chapter}</div><div><strong>Position:</strong> ${position}</div>" +
                        "<div><strong>Tags:</strong> ${tags}</div><div><strong>Created:</strong> ${createdAt}</div>" +
                        "<div><strong>Updated:</strong> ${updatedAt}</div></div><blockquote>${quote}</blockquote>" +
                        "<pre>${note}</pre></article>"
        );
    }

    public AnnotationExportTemplates normalized() {
        AnnotationExportTemplates d = defaults();
        return new AnnotationExportTemplates(
                ensureItemsPlaceholder(nonBlank(markdownDocument, d.markdownDocument), false),
                nonBlank(markdownItem, d.markdownItem),
                ensureItemsPlaceholder(nonBlank(htmlDocument, d.htmlDocument), true),
                nonBlank(htmlItem, d.htmlItem));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String ensureItemsPlaceholder(String template, boolean html) {
        if (template.contains("${items}")) return template;
        if (!html) return template + "\n${items}";
        String lower = template.toLowerCase(java.util.Locale.ROOT);
        int marker = lower.lastIndexOf("</body>");
        if (marker < 0) marker = lower.lastIndexOf("</html>");
        if (marker < 0) return template + "${items}";
        return template.substring(0, marker) + "${items}" + template.substring(marker);
    }
}
