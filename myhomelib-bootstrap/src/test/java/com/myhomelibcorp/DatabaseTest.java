package com.myhomelibcorp;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class DatabaseTest {

    private static final Path TEMP_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "myhomelib-test-" + UUID.randomUUID()
    );

    static {
        try {
            Files.createDirectories(TEMP_DIR);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private BookQueryRepository bookQueryRepository;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEMP_DIR.resolve("library-test.db"));
        registry.add("app.search.index-path", () -> TEMP_DIR.resolve("search-index").toString());
    }

    @Test
    void testDatabaseConnection() {
        // Використовуємо новий метод find з BookQuery
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(10, 0))
                .build();
        var books = bookQueryRepository.find(query);
        assertThat(books).isNotNull();
    }
}