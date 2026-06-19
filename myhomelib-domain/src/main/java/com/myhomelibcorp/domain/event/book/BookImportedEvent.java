package com.myhomelibcorp.domain.event.book;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.event.BaseDomainEvent;

public class BookImportedEvent extends BaseDomainEvent {
    private final BookId bookId;

    public BookImportedEvent(BookId bookId) {
        super("BOOK_IMPORTED");
        this.bookId = bookId;
    }

    public BookId getBookId() {
        return bookId;
    }
}