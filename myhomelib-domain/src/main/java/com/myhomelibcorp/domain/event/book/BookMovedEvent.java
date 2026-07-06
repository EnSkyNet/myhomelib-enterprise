package com.myhomelibcorp.domain.event.book;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.event.BaseDomainEvent;

public class BookMovedEvent extends BaseDomainEvent {
    private final BookId bookId;
    private final String sourcePath;
    private final String targetPath;

    public BookMovedEvent(BookId bookId, String sourcePath, String targetPath) {
        super("BOOK_MOVED");
        this.bookId = bookId;
        this.sourcePath = sourcePath;
        this.targetPath = targetPath;
    }

    public BookId getBookId() {
        return bookId;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getTargetPath() {
        return targetPath;
    }
}