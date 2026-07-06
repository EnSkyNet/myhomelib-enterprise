package com.myhomelibcorp.domain.event.book;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.event.BaseDomainEvent;

public class CoverUpdatedEvent extends BaseDomainEvent {
    private final BookId bookId;
    private final String coverHash;

    public CoverUpdatedEvent(BookId bookId, String coverHash) {
        super("COVER_UPDATED");
        this.bookId = bookId;
        this.coverHash = coverHash;
    }

    public BookId getBookId() {
        return bookId;
    }

    public String getCoverHash() {
        return coverHash;
    }
}