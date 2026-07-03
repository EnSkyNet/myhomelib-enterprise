package com.myhomelibcorp.infrastructure.persistence.postgres;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Profile("postgres")
@RequiredArgsConstructor
@Slf4j
public class PostgresBookRepository implements BookQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<Book> findById(BookId id) {
        log.warn("PostgresBookRepository.findById() not implemented yet");
        return Optional.empty();
    }

    @Override
    public List<Book> findByIds(List<BookId> ids) {
        log.warn("PostgresBookRepository.findByIds() not implemented yet");
        return List.of();
    }

    @Override
    public List<Book> find(BookQuery query) {
        log.warn("PostgresBookRepository.find() not implemented yet");
        return List.of();
    }

    @Override
    public long count(BookQuery query) {
        log.warn("PostgresBookRepository.count() not implemented yet");
        return 0;
    }

    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        log.warn("PostgresBookRepository.findByTitleAndAuthor() not implemented yet");
        return Optional.empty();
    }
}