package com.myhomelibcorp.infrastructure.persistence;

import com.myhomelibcorp.infrastructure.persistence.sqlite.TestCollectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionTransactionExecutorTest {
    @TempDir Path tempDir;

    @Test
    void executeCommitsAndReturnsValue() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("commit.db").toAbsolutePath());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE tx_probe(id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
        var manager = manager(jdbc, dataSource);

        String result = CollectionTransactionExecutor.execute(manager, () -> {
            jdbc.update("INSERT INTO tx_probe(id,value) VALUES(1,'ok')");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT value FROM tx_probe WHERE id=1", String.class)).isEqualTo("ok");
    }

    @Test
    void runRollsBackWhenWorkFails() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("rollback.db").toAbsolutePath());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE tx_probe(id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
        var manager = manager(jdbc, dataSource);

        assertThatThrownBy(() -> CollectionTransactionExecutor.run(manager, () -> {
            jdbc.update("INSERT INTO tx_probe(id,value) VALUES(1,'nope')");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tx_probe", Integer.class)).isZero();
    }

    @Test
    void missingCurrentCollectionFailsClosed() {
        var metadata = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite::memory:"));
        var manager = new TestCollectionManager(metadata);

        assertThatThrownBy(() -> CollectionTransactionExecutor.execute(manager, () -> "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Current collection");
    }

    private static TestCollectionManager manager(JdbcTemplate jdbc, javax.sql.DataSource dataSource) {
        var manager = new TestCollectionManager(jdbc);
        manager.setCurrentJdbcTemplate(jdbc);
        manager.setCurrentDataSource(dataSource);
        return manager;
    }
}
