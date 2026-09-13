package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.search.*;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteSavedSearchRepositorySmartCollectionTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteSavedSearchRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:file:saved-search-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(2);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        jdbc = new JdbcTemplate(dataSource);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentCollection(new Collection("saved", "Saved", Path.of("."), null, 1,
                null, null, null, null));
        manager.setCurrentDataSource(dataSource);
        manager.setCurrentJdbcTemplate(jdbc);
        repository = new SqliteSavedSearchRepository(new QueryExecutor(manager), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void roundTripsSmartCollectionDefinitionAndPin() {
        SmartCollectionSpec spec = new SmartCollectionSpec(
                SmartCollectionMode.AND,
                List.of(
                        SmartCollectionRule.text(SmartCollectionField.LANGUAGE, SmartCollectionOperator.EQUALS, "uk"),
                        SmartCollectionRule.number(SmartCollectionField.RATING, SmartCollectionOperator.AT_LEAST, 4, null)),
                SmartCollectionSort.RATING,
                SmartCollectionSortDirection.DESC,
                500);
        SavedSearch smart = SavedSearch.smartCollection("Top Ukrainian", spec, true);

        repository.save(smart);
        SavedSearch restored = repository.findByName("Top Ukrainian").orElseThrow();

        assertThat(restored.isSmartCollection()).isTrue();
        assertThat(restored.isPinned()).isTrue();
        assertThat(restored.getSmartCollection()).isEqualTo(spec);
        assertThat(restored.getQuery()).isEmpty();
    }

    @Test
    void migrationDefaultsLegacyRowsAndPinnedItemsSortFirst() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 8, 20, 0);
        jdbc.update("""
                INSERT INTO saved_searches(id,name,query,filters,created_at,last_used,use_count)
                VALUES('legacy','Legacy','space','{}',?,?,2)
                """, now.toString(), now.toString());

        SmartCollectionSpec spec = SmartCollectionSpec.of(SmartCollectionMode.AND, List.of(
                SmartCollectionRule.text(SmartCollectionField.FORMAT, SmartCollectionOperator.EQUALS, "fb2")));
        repository.save(SavedSearch.smartCollection("Pinned", spec, true));

        SavedSearch legacy = repository.findById("legacy").orElseThrow();
        assertThat(legacy.getKind()).isEqualTo(SavedSearchKind.SEARCH);
        assertThat(legacy.isPinned()).isFalse();
        assertThat(repository.findAll()).extracting(SavedSearch::getName)
                .containsExactly("Pinned", "Legacy");
    }
}
