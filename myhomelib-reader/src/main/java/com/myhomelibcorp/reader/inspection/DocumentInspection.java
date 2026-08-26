package com.myhomelibcorp.reader.inspection;

import java.util.List;

/**
 * File-derived details used by the rich annotation panel. The catalogue remains
 * authoritative for user data; this snapshot contains only metadata that can be
 * safely discovered from the local document itself.
 */
public record DocumentInspection(
        boolean parsed,
        String format,
        String title,
        List<String> authors,
        String language,
        String sourceLanguage,
        String publisher,
        String year,
        String isbn,
        String annotation,
        long characterCount,
        long wordCount,
        int chapterCount,
        List<TocPreviewEntry> tocPreview,
        List<DocumentImageInfo> images,
        String warning
) {
    public DocumentInspection {
        format = format == null ? "" : format;
        title = title == null ? "" : title;
        authors = authors == null ? List.of() : List.copyOf(authors);
        language = language == null ? "" : language;
        sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
        publisher = publisher == null ? "" : publisher;
        year = year == null ? "" : year;
        isbn = isbn == null ? "" : isbn;
        annotation = annotation == null ? "" : annotation;
        characterCount = Math.max(0, characterCount);
        wordCount = Math.max(0, wordCount);
        chapterCount = Math.max(0, chapterCount);
        tocPreview = tocPreview == null ? List.of() : List.copyOf(tocPreview);
        images = images == null ? List.of() : List.copyOf(images);
        warning = warning == null ? "" : warning;
    }

    public static DocumentInspection unsupported(String format, String warning) {
        return new DocumentInspection(false, format, "", List.of(), "", "", "", "", "", "",
                0, 0, 0, List.of(), List.of(), warning);
    }
}
