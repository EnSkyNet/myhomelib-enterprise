package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.filter.BookFilterMode;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookQuickFilterField;
import com.myhomelibcorp.application.query.book.BookFormat;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SqliteNavigationFacetRepository.class, SqliteNavigationFacetRepositoryStage8Test.TestConfig.class})
class SqliteNavigationFacetRepositoryStage8Test {

    @Configuration
    static class TestConfig {
        @Bean @Primary
        DataSource testDataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:sqlite:file:navfacetstage8?mode=memory&cache=shared");
            ds.setDriverClassName("org.sqlite.JDBC");
            ds.setMaximumPoolSize(3);
            return ds;
        }

        @Bean
        JdbcTemplate metadataJdbcTemplate(DataSource testDataSource) {
            return new JdbcTemplate(testDataSource);
        }

        @Bean
        CollectionManager collectionManager(DataSource testDataSource,
                                            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate) {
            TestCollectionManager manager = new TestCollectionManager(metadataJdbcTemplate);
            manager.setCurrentCollection(new Collection("test", "Test", Path.of("."), null, 0,
                    null, null, null, null));
            manager.setCurrentDataSource(testDataSource);
            manager.setCurrentJdbcTemplate(new JdbcTemplate(testDataSource));
            Flyway.configure().dataSource(testDataSource).locations("classpath:db/migration")
                    .baselineOnMigrate(true).load().migrate();
            return manager;
        }
    }

    @Autowired private SqliteNavigationFacetRepository repository;
    @Autowired private CollectionManager collectionManager;

    @BeforeEach
    void seed() {
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        jdbc.update("DELETE FROM book_genres");
        jdbc.update("DELETE FROM book_authors");
        jdbc.update("DELETE FROM books");
        jdbc.update("DELETE FROM genres");
        jdbc.update("DELETE FROM series");
        jdbc.update("DELETE FROM authors");

        jdbc.update("INSERT INTO authors(id, first_name, last_name, search_name) VALUES ('a1','Ivan','Abramov','abramov ivan')");
        jdbc.update("INSERT INTO authors(id, first_name, last_name, search_name) VALUES ('b1','Bob','Brown','brown bob')");
        jdbc.update("INSERT INTO series(id,name,description) VALUES ('s1','Chronicles','')");
        jdbc.update("INSERT INTO genres(code,name) VALUES ('sf','Sci-Fi')");
        jdbc.update("INSERT INTO books(id,title,file_name,language,year,local,progress,rate,deleted,series,format) " +
                "VALUES ('x1','Alpha History','alpha.fb2','uk',2022,1,100,5,0,'Chronicles','FB2')");
        jdbc.update("INSERT INTO books(id,title,file_name,language,year,local,progress,rate,deleted,format) " +
                "VALUES ('x2','Beta Space','beta.epub','en',2025,0,10,2,0,'EPUB')");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('x1','a1'),('x2','b1')");
        jdbc.update("INSERT INTO book_genres(book_id,genre_code) VALUES ('x1','sf')");
    }

    @Test
    void authorsSeriesGenresAndYearsRespectSameActiveFilter() {
        BookFilterSpec ukLocal = new BookFilterSpec(BookFilterMode.AND, "uk", null, null, null,
                true, null, null, null, false, BookQuickFilterField.ANY, null);

        assertThat(repository.findFirstAuthorInitial(ukLocal)).contains('A');
        assertThat(repository.findAuthors('A', ukLocal)).extracting(f -> f.id()).containsExactly("a1");
        assertThat(repository.findSeries(ukLocal)).extracting(f -> f.id()).containsExactly("s1");
        assertThat(repository.findGenres(ukLocal)).extracting(f -> f.id()).containsExactly("sf");
        assertThat(repository.findYears(ukLocal)).extracting(f -> f.id()).containsExactly("2022");
    }

    @Test
    void formatAndQuickFilterChangeFacetCountsAtSqlLevel() {
        BookFilterSpec filter = new BookFilterSpec(BookFilterMode.AND, null, null, null, BookFormat.EPUB,
                null, null, null, null, false, BookQuickFilterField.TITLE, "eta");
        assertThat(repository.findYears(filter)).extracting(f -> f.id()).containsExactly("2025");
        assertThat(repository.findAuthors('B', filter)).extracting(f -> f.bookCount()).containsExactly(1L);
    }

    @Test
    void authorPagingIsAppliedInSqlAndReturnsExactTotal() {
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        jdbc.update("INSERT INTO authors(id, first_name, last_name) VALUES ('a2','Alice','Anderson')");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('x1','a2')");

        var first = repository.findAuthorsPage('A', BookFilterSpec.empty(), 1, 0);
        var second = repository.findAuthorsPage('A', BookFilterSpec.empty(), 1, 1);

        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.content()).extracting(f -> f.id()).containsExactly("a1");
        assertThat(second.totalElements()).isEqualTo(2);
        assertThat(second.content()).extracting(f -> f.id()).containsExactly("a2");
    }

    @Test
    void serverSideAuthorSearchRespectsActiveBookFilter() {
        assertThat(repository.searchAuthors("ivan", BookFilterSpec.empty(), 20))
                .extracting(f -> f.id()).containsExactly("a1");

        BookFilterSpec englishOnly = new BookFilterSpec(BookFilterMode.AND, "en", null, null, null,
                null, null, null, null, false, BookQuickFilterField.ANY, null);
        assertThat(repository.searchAuthors("ivan", englishOnly, 20)).isEmpty();
    }
    @Test
    void serverSideAuthorSearchIsUnicodeCaseInsensitiveAndSupportsPartialCyrillic() {
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        jdbc.update("INSERT INTO authors(id, first_name, last_name, search_name) VALUES ('c1','Михаил','Боярский','боярский михаил')");
        jdbc.update("INSERT INTO authors(id, first_name, last_name, search_name) VALUES ('c2','ІЇЄҐ','Тест','тест іїєґ')");
        jdbc.update("INSERT INTO authors(id, first_name, last_name, search_name) VALUES ('c3','ЁЙ','Тестов','тестов ёй')");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('x1','c1'),('x1','c2'),('x1','c3')");

        assertThat(repository.searchAuthors("Боярский", BookFilterSpec.empty(), 20))
                .extracting(f -> f.id()).containsExactly("c1");
        assertThat(repository.searchAuthors("боярский", BookFilterSpec.empty(), 20))
                .extracting(f -> f.id()).containsExactly("c1");
        assertThat(repository.searchAuthors("бояр", BookFilterSpec.empty(), 20))
                .extracting(f -> f.id()).containsExactly("c1");
        assertThat(repository.searchAuthors("ІЇЄҐ", BookFilterSpec.empty(), 20))
                .extracting(f -> f.id()).containsExactly("c2");
        assertThat(repository.searchAuthors("ЁЙ", BookFilterSpec.empty(), 20))
                .extracting(f -> f.id()).containsExactly("c3");
    }

    @Test
    void authorKeysetPageIsBoundedAndDoesNotRunExactTotalAggregation() {
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        jdbc.update("INSERT INTO authors(id, first_name, last_name, search_name) VALUES ('a2','Alice','Anderson','anderson alice')");
        jdbc.update("INSERT INTO authors(id, first_name, last_name, search_name) VALUES ('a3','Amy','Archer','archer amy')");
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES ('x1','a2'),('x1','a3')");

        var first = repository.findAuthorsAfter('A', BookFilterSpec.empty(), 1, null);

        assertThat(first.totalElements()).isEmpty();
        assertThat(first.content()).extracting(f -> f.id()).containsExactly("a1");
        assertThat(first.nextCursor()).isNotNull();

        var second = repository.findAuthorsAfter('A', BookFilterSpec.empty(), 1, first.nextCursor());
        assertThat(second.totalElements()).isEmpty();
        assertThat(second.content()).extracting(f -> f.id()).containsExactly("a2");
        assertThat(second.nextCursor()).isNotNull();
    }


}
