package com.myhomelibcorp.infrastructure.search;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchDocument {
    private String id;          // bookId as string
    private String title;
    private String authors;     // concatenated author names
    private String series;
    private String genres;      // concatenated genre names
    private String keywords;
    private String annotation;
}