package com.myhomelibcorp.infrastructure.customfield;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDefinition;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDeletePolicy;
import com.myhomelibcorp.domain.model.customfield.CustomFieldType;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.persistence.sqlite.TestCollectionManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteCustomFieldRepositoryIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void definitionsAndTypedValuesRoundTripAcrossRestartAndDeletePolicyIsExplicit() {
        Path file = tempDir.resolve("custom.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('11111111-1111-1111-1111-111111111111','Book','book.fb2',0,1)");
        var repo = new SqliteCustomFieldRepository(manager(jdbc, ds), new ObjectMapper());
        var rating = repo.saveDefinition(new CustomFieldDefinition(null, "Score", CustomFieldType.NUMBER, List.of()));
        var status = repo.saveDefinition(new CustomFieldDefinition(null, "Status", CustomFieldType.ENUM, List.of("Draft", "Ready")));
        BookId bookId = BookId.fromString("11111111-1111-1111-1111-111111111111");
        repo.setValue(bookId, rating.id(), "12.50");
        repo.setValue(bookId, status.id(), "Ready");

        var reopened = new SqliteCustomFieldRepository(manager(new JdbcTemplate(ds), ds), new ObjectMapper());
        assertThat(reopened.findDefinitions()).extracting(CustomFieldDefinition::name).containsExactly("Score", "Status");
        assertThat(reopened.findValues(bookId).get(rating.id()).value()).isEqualTo("12.5");
        assertThat(reopened.findValues(bookId).get(status.id()).value()).isEqualTo("Ready");
        assertThatThrownBy(() -> reopened.deleteDefinition(status.id(), CustomFieldDeletePolicy.REJECT_IF_VALUES))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("values");
        reopened.deleteDefinition(status.id(), CustomFieldDeletePolicy.CASCADE_VALUES);
        assertThat(reopened.findDefinition(status.id())).isEmpty();
    }

    @Test
    void enumAndTypeChangesCannotInvalidateExistingValues() {
        Path file = tempDir.resolve("custom-edit.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('11111111-1111-1111-1111-111111111111','Book','book.fb2',0,1)");
        var repo = new SqliteCustomFieldRepository(manager(jdbc, ds), new ObjectMapper());
        var definition = repo.saveDefinition(new CustomFieldDefinition(null, "State", CustomFieldType.ENUM, List.of("A", "B")));
        BookId bookId = BookId.fromString("11111111-1111-1111-1111-111111111111");
        repo.setValue(bookId, definition.id(), "B");
        assertThatThrownBy(() -> repo.saveDefinition(new CustomFieldDefinition(definition.id(), "State", CustomFieldType.TEXT, List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> repo.saveDefinition(new CustomFieldDefinition(definition.id(), "State", CustomFieldType.ENUM, List.of("A"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ENUM");
    }

    private static TestCollectionManager manager(JdbcTemplate jdbc, javax.sql.DataSource ds) {
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentJdbcTemplate(jdbc); manager.setCurrentDataSource(ds); return manager;
    }
}
