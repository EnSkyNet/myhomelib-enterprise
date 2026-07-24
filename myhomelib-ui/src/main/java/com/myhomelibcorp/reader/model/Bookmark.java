package com.myhomelibcorp.reader.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class Bookmark {
    private String id;
    private String bookId;
    private String title;
    private String context;
    private double position;
    private int chapterIndex;
    private String chapterTitle;
    private LocalDateTime createdAt;

    public static Bookmark create(String bookId, double position, String context, String chapterTitle) {
        return Bookmark.builder()
                .id(UUID.randomUUID().toString())
                .bookId(bookId)
                .title(context != null && context.length() > 50 ? context.substring(0, 47) + "..." : context)
                .context(context)
                .position(position)
                .chapterTitle(chapterTitle)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public String getDisplayText() {
        if (title != null && !title.isEmpty()) {
            return title;
        }
        if (chapterTitle != null && !chapterTitle.isEmpty()) {
            return chapterTitle;
        }
        return "Закладка " + createdAt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
    }
}