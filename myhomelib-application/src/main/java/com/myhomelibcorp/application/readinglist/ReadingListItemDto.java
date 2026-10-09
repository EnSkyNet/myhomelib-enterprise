package com.myhomelibcorp.application.readinglist;

public record ReadingListItemDto(String bookId, int position, String title, String authors,
                                 String series, Integer sequenceNumber, boolean local, double progress) { }
