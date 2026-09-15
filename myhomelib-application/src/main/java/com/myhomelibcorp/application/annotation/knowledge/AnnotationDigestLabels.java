package com.myhomelibcorp.application.annotation.knowledge;

/** Caller-provided labels keep the application export language-neutral. */
public record AnnotationDigestLabels(
        String title,
        String author,
        String noChapter,
        String quote,
        String highlight,
        String note,
        String tags,
        String openInMyHomeLib
) {
    public AnnotationDigestLabels {
        title = safe(title, "Annotation digest");
        author = safe(author, "Author");
        noChapter = safe(noChapter, "No chapter");
        quote = safe(quote, "Quote");
        highlight = safe(highlight, "Highlight");
        note = safe(note, "Note");
        tags = safe(tags, "Tags");
        openInMyHomeLib = safe(openInMyHomeLib, "Open in MyHomeLib");
    }

    public static AnnotationDigestLabels defaults() {
        return new AnnotationDigestLabels(
                "MyHomeLib — Annotation digest", "Author", "No chapter", "Quote",
                "Highlight", "Note", "Tags", "Open in MyHomeLib");
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
