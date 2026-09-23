package com.myhomelibcorp.organizer.metadata;

import com.myhomelibcorp.organizer.detect.DetectionConfidence;

import java.util.List;

public record ExtractedBookMetadata(String rawTitle, List<String> rawAuthors, String rawSeries,
                                    String rawSeriesNumber, String language, String isbn,
                                    String publisher, Integer year, String annotation,
                                    List<String> genres, boolean coverPresent, String sourceFormat,
                                    DetectionConfidence sourceConfidence, List<String> warnings) {
    public ExtractedBookMetadata {
        rawTitle = value(rawTitle); rawAuthors = List.copyOf(rawAuthors == null ? List.of() : rawAuthors);
        rawSeries = value(rawSeries); rawSeriesNumber = value(rawSeriesNumber); language = value(language);
        isbn = value(isbn); publisher = value(publisher); annotation = value(annotation);
        genres = List.copyOf(genres == null ? List.of() : genres); sourceFormat = value(sourceFormat);
        sourceConfidence = sourceConfidence == null ? DetectionConfidence.UNKNOWN : sourceConfidence;
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
    private static String value(String v) { return v == null ? "" : v; }
}
