package com.myhomelibcorp.reader.model;

import lombok.Builder;
import lombok.Value;

/**
 * Стабільна позиція читання, яка використовується для:
 * - збереження прогресу
 * - відновлення позиції
 * - закладок
 * - навігації по змісту
 */
@Value
@Builder
public class ReaderPosition {
    String bookId;
    String paragraphId;
    int charOffset;
    double percent;
    String chapterId;
    String chapterTitle;
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
        return chapterTitle != null && !chapterTitle.isEmpty();
    }

    public String getDisplayText() {
        if (hasChapter()) {
            return chapterTitle + " · " + (int) percent + "%";
        }
        return (int) percent + "%";
    }
}