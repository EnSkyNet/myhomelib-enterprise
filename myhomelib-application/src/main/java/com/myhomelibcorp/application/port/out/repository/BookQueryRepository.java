package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Optional;

public interface BookQueryRepository {
    Optional<Book> findById(BookId id);
    List<Book> findByIds(List<BookId> ids);
    List<Book> find(BookQuery query);
    long count(BookQuery query);
    Optional<Book> findByTitleAndAuthor(String title, String authorLastName);
    List<Book> findAll();

    // Нові методи для Dashboard
    List<Book> findRecent(int limit);
    List<Book> findRecentlyAdded(int limit);
    List<Book> findFavoriteAuthors(int limit);
}