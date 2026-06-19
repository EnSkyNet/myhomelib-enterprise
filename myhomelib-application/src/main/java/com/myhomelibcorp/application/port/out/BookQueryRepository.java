package com.myhomelibcorp.application.port.out;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Optional;

public interface BookQueryRepository {
    List<Book> findAll(int limit, int offset);
    Optional<Book> findById(BookId id);
    List<Book> findByAuthorId(AuthorId authorId, int limit, int offset);
    List<Book> search(String query, int limit);
    Optional<Book> findByTitleAndAuthor(String title, String authorLastName);
    int getTotalCount();
}