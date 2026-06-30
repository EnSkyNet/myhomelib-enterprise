package com.myhomelibcorp.domain.event.book;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.event.BaseDomainEvent;

public class BookUpdatedEvent extends BaseDomainEvent {
    private final BookId bookId;

    public BookUpdatedEvent(BookId bookId) {
        super("BOOK_UPDATED");
        this.bookId = bookId;
    }

    public BookId getBookId() {
        return bookId;
    }
}