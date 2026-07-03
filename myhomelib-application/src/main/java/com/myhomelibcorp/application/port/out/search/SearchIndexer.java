package com.myhomelibcorp.application.port.out.search;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

public interface SearchIndexer {
    void indexBook(Book book);
    void indexSnapshot(BookSnapshot snapshot);
    void indexAll(List<Book> books);
    void deleteBook(BookId bookId);
    void rebuildIndex();
    int getDocumentCount();

    /**
     * Явний commit змін до індексу.
     * Після виклику індекс стає видимим для пошуку.
     */
    void commit();
}