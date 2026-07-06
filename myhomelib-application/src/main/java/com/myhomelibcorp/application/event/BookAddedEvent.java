package com.myhomelibcorp.application.event;

import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.time.Instant;

public record BookAddedEvent(
        BookId bookId,
        BookSnapshot snapshot,
        Instant timestamp
) {
    public BookAddedEvent(BookSnapshot snapshot) {
        this(snapshot.getId(), snapshot, Instant.now());
    }
}