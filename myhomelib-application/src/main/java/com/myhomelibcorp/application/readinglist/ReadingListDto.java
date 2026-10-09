package com.myhomelibcorp.application.readinglist;

import java.time.LocalDateTime;

public record ReadingListDto(String id, String name, String description, int itemCount,
                             LocalDateTime createdAt, LocalDateTime updatedAt) { }
