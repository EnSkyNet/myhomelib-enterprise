package com.myhomelibcorp.reader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@AllArgsConstructor
public class ReaderPosition {
    String bookId;
    String chapterId;
    String chapterTitle;  // залишаємо для зручності відображення
    String paragraphId;
    int paragraphIndex;   // залишаємо для зворотної сумісності
    int charOffset;
    double percent;

    public static ReaderPosition empty(String bookId) {
        return ReaderPosition.builder()
                .bookId(bookId)
                .paragraphId("")
                .paragraphIndex(0)
                .charOffset(0)
                .percent(0.0)
                .chapterId("")
                .chapterTitle("")
                .build();
    }

    public boolean isValid() {
        return paragraphId != null && !paragraphId.isEmpty();
    }

    public boolean hasChapter() {
        return chapterId != null && !chapterId.isEmpty();
    }

    public String getDisplayText() {
        return (int) percent + "%";
    }
}