package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
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

    @Test
    void testFindBooks() {
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(10, 0))
                .build();
        var books = repository.find(query);
        assertThat(books).isNotNull();
        assertThat(books).isEmpty();
    }
}