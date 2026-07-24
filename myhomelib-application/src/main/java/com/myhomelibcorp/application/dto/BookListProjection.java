package com.myhomelibcorp.application.dto;

import lombok.Value;

@Value
public class BookListProjection {
    String id;
    String title;
    String authorsText;
    String series;
    String genresText;
    int rate;
    int progress;
    long fileSize;
    String language;
    String fileName;
    String folder;
    String collectionRoot;
}