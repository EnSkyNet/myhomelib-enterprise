package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.infrastructure.persistence.mapper.GroupRowMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteGroupRepositoryTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteGroupRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:file:groups-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(3);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentCollection(new Collection("c1", "Test", Path.of("."), null, 1, null, null, null, null));
        manager.setCurrentDataSource(dataSource);
        manager.setCurrentJdbcTemplate(jdbc);
        repository = new SqliteGroupRepository(manager, new GroupRowMapper());
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void saveReturnsIdFromTheSameInsertStatement() {
        Group saved = repository.save(new Group("Generated ID", true));

        assertThat(saved.getIdAsLong()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT name FROM groups WHERE id = ?", String.class, saved.getIdAsLong()))
                .isEqualTo("Generated ID");
    }

    @Test
    void deleteRollsBackMembershipRemovalWhenGroupDeleteFails() {
        Group saved = repository.save(new Group("Rollback", true));
        jdbc.update("INSERT INTO books(id,title,file_name,local) VALUES ('book-1','Book','book.fb2',1)");
        repository.addBookToGroup(saved.getIdAsLong(), "book-1");
        jdbc.execute("""
                CREATE TRIGGER fail_group_delete
                BEFORE DELETE ON groups
                BEGIN
                    SELECT RAISE(ABORT, 'forced group delete failure');
                END
                """);

        assertThatThrownBy(() -> repository.deleteById(saved.getIdAsLong()))
                .isInstanceOf(RuntimeException.class);

        Integer memberships = jdbc.queryForObject(
                "SELECT COUNT(*) FROM book_groups WHERE group_id = ? AND book_id = 'book-1'",
                Integer.class, saved.getIdAsLong());
        assertThat(memberships).isEqualTo(1);
        assertThat(repository.findById(saved.getIdAsLong())).isPresent();
    }
}
