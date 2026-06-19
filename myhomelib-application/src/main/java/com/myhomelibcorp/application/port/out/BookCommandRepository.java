package com.myhomelibcorp.application.port.out;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;

public interface BookCommandRepository {
    Book save(Book book);
    void deleteById(BookId id);
    void updateRate(BookId bookId, int rate);
    void updateProgress(BookId bookId, int progress);
}