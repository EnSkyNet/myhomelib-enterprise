package com.myhomelibcorp.infrastructure.persistence.postgres;

import com.myhomelibcorp.application.port.out.BookCommandRepository;
import com.myhomelibcorp.application.port.out.BookQuery;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

//@Repository
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
        log.warn("PostgresBookRepository.findAll() ще не реалізовано");
        return List.of();
    }

    @Override
    public Optional<Book> findById(BookId id) {
        log.warn("PostgresBookRepository.findById() ще не реалізовано");
        return Optional.empty();
    }

    @Override
    public List<Book> findByIds(List<BookId> ids) {
        log.warn("PostgresBookRepository.findByIds() ще не реалізовано");
        return List.of();
    }

    @Override
    public List<Book> findByAuthorId(AuthorId authorId, int limit, int offset) {
        log.warn("PostgresBookRepository.findByAuthorId() ще не реалізовано");
        return List.of();
    }

    @Override
    public List<Book> search(String query, int limit) {
        log.warn("PostgresBookRepository.search() ще не реалізовано");
        return List.of();
    }

    @Override
    public List<Book> searchByAuthor(String authorName, int limit) {
        log.warn("PostgresBookRepository.searchByAuthor() ще не реалізовано");
        return List.of();
    }

    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        log.warn("PostgresBookRepository.findByTitleAndAuthor() ще не реалізовано");
        return Optional.empty();
    }

    @Override
    public int getTotalCount() {
        log.warn("PostgresBookRepository.getTotalCount() ще не реалізовано");
        return 0;
    }

    @Override
    public List<Book> findBySeries(String seriesName, int limit, int offset) {
        log.warn("PostgresBookRepository.findBySeries() ще не реалізовано");
        return List.of();
    }

    @Override
    public List<Book> findByGenre(String genreCode, int limit, int offset) {
        log.warn("PostgresBookRepository.findByGenre() ще не реалізовано");
        return List.of();
    }

    // ========== НОВИЙ МЕТОД ==========
    @Override
    public List<Book> find(BookQuery query) {
        log.warn("PostgresBookRepository.find() ще не реалізовано");
        return List.of();
    }

    // === BookCommandRepository ===

    @Override
    @Transactional
    public Book save(Book book) {
        log.warn("PostgresBookRepository.save() ще не реалізовано");
        return book;
    }

    @Override
    @Transactional
    public void saveBatch(List<Book> books) {
        log.warn("PostgresBookRepository.saveBatch() ще не реалізовано");
    }

    @Override
    public void deleteById(BookId id) {
        log.warn("PostgresBookRepository.deleteById() ще не реалізовано");
    }

    @Override
    public void updateRate(BookId bookId, int rate) {
        log.warn("PostgresBookRepository.updateRate() ще не реалізовано");
    }

    @Override
    public void updateProgress(BookId bookId, int progress) {
        log.warn("PostgresBookRepository.updateProgress() ще не реалізовано");
    }
}