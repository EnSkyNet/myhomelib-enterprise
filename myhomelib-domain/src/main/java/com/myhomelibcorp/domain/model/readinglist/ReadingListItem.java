package com.myhomelibcorp.domain.model.readinglist;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import java.time.LocalDateTime;
import java.util.Objects;

public record ReadingListItem(BookId bookId, int position, LocalDateTime addedAt) {
    public ReadingListItem {
        Objects.requireNonNull(bookId, "bookId");
        if (position < 0) throw new IllegalArgumentException("Reading-list position cannot be negative");
        if (addedAt == null) addedAt = LocalDateTime.now();
    }
}
