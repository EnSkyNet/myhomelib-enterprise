package com.myhomelibcorp.reader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@AllArgsConstructor
public class ReaderPosition {
    String bookId;
    String chapterId;        // <-- НОВЕ ПОЛЕ: ID розділу
    String chapterTitle;
    String paragraphId;      // <-- ВИКОРИСТОВУЄТЬСЯ ЗАМІСТЬ ІНДЕКСУ
    int charOffset;
    double percent;

    // @Deprecated - залишено для зворотної сумісності
    @Deprecated
    int paragraphIndex;

    public static ReaderPosition empty(String bookId) {
        return ReaderPosition.builder()
                .bookId(bookId)
                .paragraphId("")
                .charOffset(0)
                .percent(0.0)
                .chapterId("")
                .chapterTitle("")
                .paragraphIndex(0)
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