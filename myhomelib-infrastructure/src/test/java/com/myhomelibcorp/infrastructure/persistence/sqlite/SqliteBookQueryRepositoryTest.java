package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class SqliteBookQueryRepositoryTest {

    @Autowired
    private SqliteBookQueryRepository repository;

    @Test
    void testFindBooks() {
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(10, 0))
                .build();
        var books = repository.find(query);
        assertThat(books).isNotNull();
    }
}