package com.myhomelibcorp.application.event;

import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.time.Instant;

public record BookUpdatedEvent(
        BookId bookId,
        BookSnapshot oldSnapshot,
        BookSnapshot newSnapshot,
        Instant timestamp
) {
    public BookUpdatedEvent(BookSnapshot oldSnapshot, BookSnapshot newSnapshot) {
        this(newSnapshot.getId(), oldSnapshot, newSnapshot, Instant.now());
    }
}