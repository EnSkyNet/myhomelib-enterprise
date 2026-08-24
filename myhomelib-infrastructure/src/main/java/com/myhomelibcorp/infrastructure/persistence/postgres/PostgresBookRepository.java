package com.myhomelibcorp.infrastructure.persistence.postgres;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
@Profile("postgres")
@RequiredArgsConstructor
@Slf4j
public class PostgresBookRepository implements BookQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    // ===== Пагінація =====
    @Override
    public PageResult<Book> findPage(BookQuery query) {
        log.warn("PostgresBookRepository.findPage() not implemented yet");
        return PageResult.empty();
    }

    @Override
    public long count(BookQuery query) {
        log.warn("PostgresBookRepository.count() not implemented yet");
        return 0;
    }

    // ===== Пошук по ID =====
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

    // ===== Пошук за зберіганням =====
    @Override
    public Optional<Book> findByStorage(String collectionRoot, String folder, String fileName, String archiveEntry) {
        log.warn("PostgresBookRepository.findByStorage() not implemented yet");
        return Optional.empty();
    }

    @Override
    public List<Book> findByArchiveContainer(String collectionRoot, String relativeArchivePath, String absoluteArchivePath) {
        log.warn("PostgresBookRepository.findByArchiveContainer() not implemented yet");
        return List.of();
    }

    @Override
    public Stream<Book> streamAll() {
        log.warn("PostgresBookRepository.streamAll() not implemented yet");
        return Stream.empty();
    }

    // ===== Спеціальні запити =====
    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        log.warn("PostgresBookRepository.findByTitleAndAuthor() not implemented yet");
        return Optional.empty();
    }

    @Override
    public List<Book> findRecent(int limit) {
        log.warn("PostgresBookRepository.findRecent() not implemented yet");
        return List.of();
    }

    @Override
    public List<Book> findRecentlyAdded(int limit) {
        log.warn("PostgresBookRepository.findRecentlyAdded() not implemented yet");
        return List.of();
    }

    @Override
    public List<Book> findFavoriteAuthors(int limit) {
        log.warn("PostgresBookRepository.findFavoriteAuthors() not implemented yet");
        return List.of();
    }

    // ===== DataIntegrity =====
    @Override
    public long countBooksWithoutAuthor() {
        log.warn("PostgresBookRepository.countBooksWithoutAuthor() not implemented yet");
        return 0;
    }

    @Override
    public long countBooksWithoutGenre() {
        log.warn("PostgresBookRepository.countBooksWithoutGenre() not implemented yet");
        return 0;
    }

    @Override
    public List<BookId> findDuplicateBookIds() {
        log.warn("PostgresBookRepository.findDuplicateBookIds() not implemented yet");
        return List.of();
    }

    // ===== @Deprecated методи =====
    @Override
    @Deprecated
    public List<Book> find(BookQuery query) {
        log.warn("PostgresBookRepository.find() not implemented yet");
        return List.of();
    }

    @Override
    @Deprecated
    public List<Book> findAll() {
        log.warn("PostgresBookRepository.findAll() not implemented yet");
        return List.of();
    }
}