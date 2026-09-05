package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookListRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        SqliteBookQueryRepository.class,
        BookRowMapper.class,
        BookListRowMapper.class,
        BookAuthorHelper.class,
        BookGenreHelper.class,
        BookQueryBuilder.class,
        SqliteBookQueryRepositoryTest.TestConfig.class
})
public class SqliteBookQueryRepositoryTest {

    @Configuration
    static class TestConfig {

        @Bean
        @Primary
        public DataSource testDataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:sqlite:file:testdb?mode=memory&cache=shared");
            ds.setDriverClassName("org.sqlite.JDBC");
            ds.setMaximumPoolSize(5);
            return ds;
        }

        @Bean
        public JdbcTemplate metadataJdbcTemplate(DataSource testDataSource) {
            return new JdbcTemplate(testDataSource);
        }

        @Bean
        public CollectionManager collectionManager(
                DataSource testDataSource,
                @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate
        ) {
            TestCollectionManager manager = new TestCollectionManager(metadataJdbcTemplate);

            Collection testCollection = new Collection(
                    "test-collection-id",
                    "Test Collection",
                    Path.of("."),
                    null,
                    0,
                    null,
                    null,
                    null,
                    null
            );

            manager.setCurrentCollection(testCollection);
            manager.setCurrentDataSource(testDataSource);
            manager.setCurrentJdbcTemplate(new JdbcTemplate(testDataSource));

            Flyway flyway = Flyway.configure()
                    .dataSource(testDataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();

            return manager;
        }

        @Bean
        @Primary
        public AuthorRepository authorRepository() {
            return mock(AuthorRepository.class);
        }

        @Bean
        @Primary
        public GenreRepository genreRepository() {
            return mock(GenreRepository.class);
        }

        @Bean
        public AuthorRowMapper authorRowMapper() {
            return new AuthorRowMapper();
        }

        @Bean
        public GenreRowMapper genreRowMapper() {
            return new GenreRowMapper();
        }
    }

    @Autowired
    private SqliteBookQueryRepository repository;

    @Autowired
    @Qualifier("metadataJdbcTemplate")
    private JdbcTemplate jdbc;

    @Test
    void testFindBooks() {
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(10, 0))
                .build();
        var books = repository.findPage(query).content();
        assertThat(books).isNotNull();
        assertThat(books).isEmpty();
    }
    @Test
    void streamSearchSnapshotsProjectsOnlyActiveBooksWithRelations() {
        jdbc.update("INSERT INTO authors(id, first_name, middle_name, last_name, search_name) VALUES (?, ?, ?, ?, ?)",
                "author-1", "Дмитрий", "Александрович", "Дорничев", "дорничев дмитрий александрович");
        jdbc.update("INSERT INTO genres(code, name) VALUES (?, ?)", "sf", "Фантастика");

        jdbc.update("""
                INSERT INTO books(id, title, file_name, language, keywords, annotation, deleted, local, year, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, 1, ?, ?)
                """, "11111111-1111-1111-1111-111111111111", "Активна книга", "active.fb2", "ru", "космос", "опис",
                2024, "2026-09-04 10:20:30.123");
        jdbc.update("INSERT INTO book_authors(book_id, author_id) VALUES (?, ?)", "11111111-1111-1111-1111-111111111111", "author-1");
        jdbc.update("INSERT INTO book_genres(book_id, genre_code) VALUES (?, ?)", "11111111-1111-1111-1111-111111111111", "sf");

        jdbc.update("""
                INSERT INTO books(id, title, file_name, deleted, local) VALUES (?, ?, ?, 1, 0)
                """, "22222222-2222-2222-2222-222222222222", "Видалена книга", "deleted.fb2");

        var snapshots = repository.streamSearchSnapshots().toList();

        assertThat(snapshots).hasSize(1);
        var snapshot = snapshots.getFirst();
        assertThat(snapshot.getId().asString()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(snapshot.getTitle()).isEqualTo("Активна книга");
        assertThat(snapshot.getAuthorsText()).isEqualTo("Дорничев Дмитрий Александрович");
        assertThat(snapshot.getAuthorIds()).isEqualTo("author-1");
        assertThat(snapshot.getGenresText()).isEqualTo("Фантастика");
        assertThat(snapshot.getGenreIds()).isEqualTo("sf");
        assertThat(snapshot.getKeywords()).isEqualTo("космос");
        assertThat(snapshot.getYear()).isEqualTo(2024);
        assertThat(snapshot.getCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 4, 10, 20, 30, 123_000_000));
        assertThat(snapshot.isLocal()).isTrue();
        assertThat(snapshot.isDeleted()).isFalse();
    }

}