package com.myhomelibcorp.domain.event.book;

import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.event.BaseDomainEvent;

public class BookAddedEvent extends BaseDomainEvent {
    private final BookSnapshot bookSnapshot;

    public BookAddedEvent(BookSnapshot bookSnapshot) {
        super("BOOK_ADDED");
        this.bookSnapshot = bookSnapshot;
    }

    public BookSnapshot getBookSnapshot() {
        return bookSnapshot;
    }

    public BookId getBookId() {
        return bookSnapshot != null ? bookSnapshot.getId() : null;
    }
}