package com.myhomelibcorp.domain.event.book;

import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.event.BaseDomainEvent;

public class BookUpdatedEvent extends BaseDomainEvent {
    private final BookSnapshot bookSnapshot;

    public BookUpdatedEvent(BookSnapshot bookSnapshot) {
        super("BOOK_UPDATED");
        this.bookSnapshot = bookSnapshot;
    }

    @Deprecated
    public BookUpdatedEvent(BookId bookId) {
        super("BOOK_UPDATED");
        this.bookSnapshot = null;
    }

    public BookSnapshot getBookSnapshot() {
        return bookSnapshot;
    }

    public BookId getBookId() {
        return bookSnapshot != null ? bookSnapshot.getId() : null;
    }
}