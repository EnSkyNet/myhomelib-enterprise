package com.myhomelibcorp;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class DatabaseTest {

    @Autowired
    private BookQueryRepository bookQueryRepository;

    @Test
    void testDatabaseConnection() {
        var books = bookQueryRepository.findAll(10, 0);
        assertThat(books).isNotNull();
        System.out.println("Books count: " + books.size());
    }
}