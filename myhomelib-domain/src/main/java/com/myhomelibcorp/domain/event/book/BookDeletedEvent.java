package com.myhomelibcorp.domain.event.book;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.event.BaseDomainEvent;

public class BookDeletedEvent extends BaseDomainEvent {
    private final BookId bookId;

    public BookDeletedEvent(BookId bookId) {
        super("BOOK_DELETED");
        this.bookId = bookId;
    }

    public BookId getBookId() {
        return bookId;
    }
}