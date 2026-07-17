package com.myhomelibcorp.reader.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class Bookmark {
    private String id;
    private String bookId;          // ID книги (з Library)
    private String title;           // Коротка назва (перші 30-50 символів)
    private String context;         // Текст навколо закладки (для відображення)
    private double position;        // scrollY позиція (0..1)
    private int chapterIndex;       // індекс розділу (якщо є)
    private String chapterTitle;    // назва розділу
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