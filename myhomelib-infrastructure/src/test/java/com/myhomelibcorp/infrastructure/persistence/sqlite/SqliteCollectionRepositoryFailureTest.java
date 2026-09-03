package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteCollectionRepositoryFailureTest {

    @Test
    void findAllDoesNotTurnMissingMetadataTableIntoEmptyLibrary() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite::memory:"));
        SqliteCollectionRepository repository = new SqliteCollectionRepository(jdbc);

        assertThrows(DataAccessException.class, repository::findAll);
    }
}
