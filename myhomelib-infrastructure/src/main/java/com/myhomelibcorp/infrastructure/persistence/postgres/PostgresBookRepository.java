package com.myhomelibcorp.infrastructure.persistence.postgres;

import com.myhomelibcorp.application.port.out.BookCommandRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Реалізація BookQueryRepository та BookCommandRepository для PostgreSQL.
 * Наразі використовується як заглушка для майбутньої міграції.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class PostgresBookRepository implements BookQueryRepository, BookCommandRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<Book> bookRowMapper = (rs, rowNum) -> {
        // TODO: реалізувати маппінг для PostgreSQL
        return null;
    };

    // === BookQueryRepository ===

    @Override
    public List<Book> findAll(int limit, int offset) {
        // TODO: реалізувати
        return List.of();
    }

    @Override
    public Optional<Book> findById(BookId id) {
        // TODO: реалізувати
        return Optional.empty();
    }

    @Override
    public List<Book> findByIds(List<BookId> ids) {
        // Заглушка – поки не реалізовано
        log.warn("findByIds() not yet implemented for PostgreSQL");
        return List.of();
    }

    @Override
    public List<Book> findByAuthorId(AuthorId authorId, int limit, int offset) {
        // TODO: реалізувати
        return List.of();
    }

    @Override
    public List<Book> search(String query, int limit) {
        // TODO: реалізувати (можливо через PostgreSQL full-text search)
        return List.of();
    }

    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        // TODO: реалізувати
        return Optional.empty();
    }

    @Override
    public int getTotalCount() {
        // TODO: реалізувати
        return 0;
    }

    // === BookCommandRepository ===

    @Override
    @Transactional
    public Book save(Book book) {
        // TODO: реалізувати
        return book;
    }

    @Override
    @Transactional
    public void saveBatch(List<Book> books) {
        // Заглушка – поки не реалізовано
        if (books == null || books.isEmpty()) {
            return;
        }
        log.warn("saveBatch() not yet implemented for PostgreSQL, saving one by one");
        for (Book book : books) {
            save(book);
        }
    }

    @Override
    public void deleteById(BookId id) {
        // TODO: реалізувати
    }

    @Override
    public void updateRate(BookId bookId, int rate) {
        // TODO: реалізувати
    }

    @Override
    public void updateProgress(BookId bookId, int progress) {
        // TODO: реалізувати
    }
}