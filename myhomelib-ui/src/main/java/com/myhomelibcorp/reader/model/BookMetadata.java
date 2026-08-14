package com.myhomelibcorp.reader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookMetadata {
    private String title;
    private List<String> authors;
    private String language;
    private String genre;
    private String annotation;
    private String series;
    private Integer sequenceNumber;
    private String publisher;
    private String year;
    private String isbn;
}