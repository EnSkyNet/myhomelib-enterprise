package com.myhomelibcorp;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBookQueryRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = DatabaseTest.TestConfig.class)
public class DatabaseTest {

    // Внутрішній тестовий CollectionManager
    static class TestCollectionManagerForTest extends CollectionManager {
        public TestCollectionManagerForTest(JdbcTemplate metadataJdbcTemplate,
                                            ApplicationEventPublisher eventPublisher) {
            super(metadataJdbcTemplate, eventPublisher, new TestDataSourceConfigForTest());
        }

        public void setCurrentCollection(Collection collection) {
            try {
                var field = CollectionManager.class.getDeclaredField("currentCollection");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.concurrent.atomic.AtomicReference<Collection> ref =
                        (java.util.concurrent.atomic.AtomicReference<Collection>) field.get(this);
                ref.set(collection);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set currentCollection", e);
            }
        }

        public void setCurrentDataSource(DataSource dataSource) {
            try {
                var field = CollectionManager.class.getDeclaredField("currentDataSource");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.concurrent.atomic.AtomicReference<DataSource> ref =
                        (java.util.concurrent.atomic.AtomicReference<DataSource>) field.get(this);
                ref.set(dataSource);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set currentDataSource", e);
            }
        }

        public void setCurrentJdbcTemplate(JdbcTemplate jdbcTemplate) {
            try {
                var field = CollectionManager.class.getDeclaredField("currentJdbcTemplate");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.concurrent.atomic.AtomicReference<JdbcTemplate> ref =
                        (java.util.concurrent.atomic.AtomicReference<JdbcTemplate>) field.get(this);
                ref.set(jdbcTemplate);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set currentJdbcTemplate", e);
            }
        }
    }

    // Тестова конфігурація DataSourceConfig
    static class TestDataSourceConfigForTest extends DataSourceConfig {
        @Override
        public HikariDataSource createDataSourceForPath(String dbPath) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:file:testdb?mode=memory&cache=shared");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            config.setIdleTimeout(30000);
            config.setMaxLifetime(60000);
            config.setConnectionTimeout(10000);
            config.setLeakDetectionThreshold(5000);
            config.setPoolName("TestPool");
            return new HikariDataSource(config);
        }
    }

    @Configuration
    @Import({
            SqliteBookQueryRepository.class,
            BookRowMapper.class,
            BookAuthorHelper.class,
            BookGenreHelper.class,
            BookQueryBuilder.class,
            AuthorRowMapper.class,
            GenreRowMapper.class
    })
    static class TestConfig {

        @Bean
        @Primary
        public DataSource testDataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:file:testdb?mode=memory&cache=shared");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            config.setIdleTimeout(30000);
            config.setMaxLifetime(60000);
            config.setConnectionTimeout(10000);
            config.setLeakDetectionThreshold(5000);
            config.setPoolName("TestPool");
            return new HikariDataSource(config);
        }

        @Bean
        public JdbcTemplate metadataJdbcTemplate(DataSource testDataSource) {
            return new JdbcTemplate(testDataSource);
        }

        @Bean
        public ApplicationEventPublisher applicationEventPublisher() {
            return mock(ApplicationEventPublisher.class);
        }

        @Bean
        public CollectionManager collectionManager(
                DataSource testDataSource,
                @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate,
                ApplicationEventPublisher eventPublisher
        ) {
            TestCollectionManagerForTest manager = new TestCollectionManagerForTest(metadataJdbcTemplate, eventPublisher);

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
    }

    @Autowired
    private BookQueryRepository bookQueryRepository;

    @Test
    void testDatabaseConnection() {
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(10, 0))
                .build();
        var books = bookQueryRepository.find(query);
        assertThat(books).isNotNull();
        assertThat(books).isEmpty();
    }
}