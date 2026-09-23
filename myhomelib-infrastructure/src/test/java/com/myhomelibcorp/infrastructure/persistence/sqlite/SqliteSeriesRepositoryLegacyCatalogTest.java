package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.SeriesRowMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
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
@Import({SqliteSeriesRepository.class, SeriesRowMapper.class, SqliteSeriesRepositoryLegacyCatalogTest.TestConfig.class})
class SqliteSeriesRepositoryLegacyCatalogTest {

    @Configuration
    static class TestConfig {
        @Bean @Primary
        DataSource testDataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:sqlite:file:serieslegacy?mode=memory&cache=shared");
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

    @Autowired private SqliteSeriesRepository repository;
    @Autowired private CollectionManager collectionManager;

    @BeforeEach
    void seedLegacyState() {
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        jdbc.update("DELETE FROM books");
        jdbc.update("DELETE FROM series");
        jdbc.update("INSERT INTO books(id,title,file_name,deleted,series,format) VALUES " +
                "('b1','One','one.fb2',0,'  The Saga  ','FB2')," +
                "('b2','Two','two.fb2',0,'the saga','FB2')," +
                "('b3','Deleted','deleted.fb2',1,'Hidden Saga','FB2')");
    }

    @Test
    void searchUsesBooksAsSourceOfTruthWhenSeriesTableIsEmpty() {
        assertThat(repository.searchNames("sag", 20)).containsExactly("The Saga");
        assertThat(collectionManager.getCurrentJdbcTemplate()
                .queryForObject("SELECT COUNT(*) FROM series", Integer.class)).isZero();
    }

    @Test
    void findByNameRepairsOnlyRequestedSeriesIdentity() {
        var found = repository.findByName("the saga");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getName()).isEqualTo("The Saga");
        assertThat(found.orElseThrow().getId().asString())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(collectionManager.getCurrentJdbcTemplate()
                .queryForObject("SELECT COUNT(*) FROM series", Integer.class)).isEqualTo(1);
    }
}
