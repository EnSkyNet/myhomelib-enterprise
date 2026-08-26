package com.myhomelibcorp.application.opds;

public record OpdsBookDto(
        String id,
        String title,
        String authors,
        String series,
        String language,
        Integer year,
        String annotation,
        String format,
        boolean local,
        String fileName,
        String archiveEntry) { }
