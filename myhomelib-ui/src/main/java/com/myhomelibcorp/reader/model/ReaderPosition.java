package com.myhomelibcorp.reader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@AllArgsConstructor
public class ReaderPosition {
    String bookId;

    // ===== ВИПРАВЛЕНО: anchorId як PRIMARY LOCATOR =====
    String anchorId;           // Стабільний ідентифікатор (новий)

    // ===== ЗАЛИШАЄМО ДЛЯ ЗВОРОТНОЇ СУМІСНОСТІ =====
    String paragraphId;        // @Deprecated - p1, p2, p3...
    String xpath;              // @Deprecated - для діагностики

    // ===== ОСНОВНІ ПОЛЯ =====
    int paragraphIndex;        // FALLBACK locator
    int charOffset;            // TOЧНЕ ПОЛОЖЕННЯ всередині параграфа
    double percent;            // PROGRESS + LAST FALLBACK
    String chapterId;
    String chapterTitle;

    public static ReaderPosition empty(String bookId) {
        return ReaderPosition.builder()
                .bookId(bookId)
                .anchorId("")
                .paragraphId("")
                .xpath("")
                .paragraphIndex(0)
                .charOffset(0)
                .percent(0.0)
                .chapterId("")
                .chapterTitle("")
                .build();
    }
}