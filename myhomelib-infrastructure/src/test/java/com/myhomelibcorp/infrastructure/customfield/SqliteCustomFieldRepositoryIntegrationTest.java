package com.myhomelibcorp.infrastructure.customfield;

import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDefinition;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDeletePolicy;
import com.myhomelibcorp.domain.model.customfield.CustomFieldType;
import com.myhomelibcorp.application.port.out.customfield.CustomFieldRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.persistence.sqlite.TestCollectionManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

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

    @Test
    void collectionTransactionsRollBackEvenWhenMetadataTransactionManagerIsPrimary() {
        Path collectionFile = tempDir.resolve("custom-transaction.db");
        Path metadataFile = tempDir.resolve("metadata-transaction.db");
        var collectionDs = new DriverManagerDataSource("jdbc:sqlite:" + collectionFile.toAbsolutePath());
        var metadataDs = new DriverManagerDataSource("jdbc:sqlite:" + metadataFile.toAbsolutePath());
        Flyway.configure().dataSource(collectionDs).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(collectionDs);
        jdbc.update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('22222222-2222-2222-2222-222222222222','Book','book.fb2',0,1)");
        jdbc.update("INSERT INTO custom_field_definitions(name,field_type,enum_options_json) VALUES('State','TEXT','[]')");
        Long definitionId = jdbc.queryForObject("SELECT id FROM custom_field_definitions WHERE name='State'", Long.class);
        assertThat(definitionId).isNotNull();
        jdbc.update("INSERT INTO custom_field_values(book_id,definition_id,value_text) VALUES(?,?,?)",
                "22222222-2222-2222-2222-222222222222", definitionId, "Ready");
        jdbc.execute("""
                CREATE TRIGGER fail_custom_definition_delete
                BEFORE DELETE ON custom_field_definitions
                BEGIN
                    SELECT RAISE(ABORT, 'forced definition delete failure');
                END
                """);

        TestCollectionManager collectionManager = manager(jdbc, collectionDs);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionTestConfig.class);
            context.registerBean("metadataTransactionManager", PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(metadataDs), definition -> definition.setPrimary(true));
            context.registerBean("collectionTransactionManager", PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(collectionDs));
            context.registerBean(com.myhomelibcorp.infrastructure.collection.CollectionManager.class, () -> collectionManager);
            context.registerBean(SqliteCustomFieldRepository.class,
                    () -> new SqliteCustomFieldRepository(collectionManager, new ObjectMapper()));
            context.refresh();

            CustomFieldRepository repository = context.getBean(CustomFieldRepository.class);
            assertThatThrownBy(() -> repository.deleteDefinition(definitionId, CustomFieldDeletePolicy.CASCADE_VALUES))
                    .isInstanceOf(RuntimeException.class);
        }

        Integer values = jdbc.queryForObject(
                "SELECT COUNT(*) FROM custom_field_values WHERE definition_id=?", Integer.class, definitionId);
        Integer definitions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM custom_field_definitions WHERE id=?", Integer.class, definitionId);
        assertThat(values).as("first delete must roll back with the failed definition delete").isEqualTo(1);
        assertThat(definitions).isEqualTo(1);
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionTestConfig { }

    private static TestCollectionManager manager(JdbcTemplate jdbc, javax.sql.DataSource ds) {
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentJdbcTemplate(jdbc); manager.setCurrentDataSource(ds); return manager;
    }
}
