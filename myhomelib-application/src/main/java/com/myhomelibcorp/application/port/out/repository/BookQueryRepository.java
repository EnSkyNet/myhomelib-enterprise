package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Optional;

public interface BookQueryRepository {

    // ===== Пошук з пагінацією =====
    PageResult<Book> findPage(BookQuery query);

    long count(BookQuery query);

    // ===== Пошук по ID =====
    Optional<Book> findById(BookId id);

    List<Book> findByIds(List<BookId> ids);

    // ===== Спеціальні запити =====
    Optional<Book> findByTitleAndAuthor(String title, String authorLastName);

    List<Book> findRecent(int limit);

    List<Book> findRecentlyAdded(int limit);

    List<Book> findFavoriteAuthors(int limit);

    // ===== Для DataIntegrity =====
    long countBooksWithoutAuthor();

    long countBooksWithoutGenre();

    List<BookId> findDuplicateBookIds();

    // ===== @Deprecated =====
    @Deprecated
    default List<Book> find(BookQuery query) {
        PageResult<Book> page = findPage(query);
        return page.content();
    }

    @Deprecated
    default List<Book> findAll() {
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(10000, 0))
                .build();
        return find(query);
    }
}