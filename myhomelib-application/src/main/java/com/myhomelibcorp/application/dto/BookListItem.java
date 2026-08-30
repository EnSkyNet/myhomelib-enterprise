package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookListItem {
    private String id;
    private String title;
    private String authorsText;
    private String series;
    private Integer sequenceNumber;
    private String genresText;
    private int rate;
    private int progress;
    private String coverHash;
    private long fileSize;
    private boolean local;
    private String updateDate;
    private String createdAt;

    // Нові поля
    private String fileName;
    private String folder;
    private String archiveEntry;
    private String collectionRoot;
    private String annotation;
    private String language;
    private Integer year;
}