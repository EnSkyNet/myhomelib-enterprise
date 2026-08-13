package com.myhomelibcorp.domain.model.bookmark;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Value
@Builder
public class Bookmark {
    String id;
    String bookId;
    String paragraphId;
    int charOffset;
    double position;
    String chapterTitle;
    String context;
    LocalDateTime createdAt;

    public String getTitle() {
        if (context != null && !context.isEmpty()) {
            String clean = context.replaceAll("\\s+", " ").trim();
            if (clean.length() > 50) {
                return clean.substring(0, 47) + "...";
            }
            return clean;
        }
        if (chapterTitle != null && !chapterTitle.isEmpty()) {
            return chapterTitle;
        }
        return "Закладка " + createdAt.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"));
    }

    public String getDisplayText() {
        return getTitle();
    }

    public String getFormattedDate() {
        return createdAt != null
                ? createdAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                : "";
    }
}