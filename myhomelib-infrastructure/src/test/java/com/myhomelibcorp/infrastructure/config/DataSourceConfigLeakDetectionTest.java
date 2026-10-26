package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DataSourceConfigLeakDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    void leakDetectionThresholdIsEnabledWithinConfiguredMaxLifetime() {
        Path db = tempDir.resolve("leak-detection.db");
        try (HikariDataSource dataSource = new DataSourceConfig().createDataSourceForPath(db.toString())) {
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(DataSourceConfig.INTERACTIVE_MAX_POOL_SIZE);
            assertThat(dataSource.getLeakDetectionThreshold()).isGreaterThanOrEqualTo(2_000L);
            assertThat(dataSource.getMaxLifetime()).isGreaterThan(dataSource.getLeakDetectionThreshold());
        }
    }

    @Test
    void thirdConnectionServesInteractiveQueriesWhenIndexingOccupiesTwoConnections() throws Exception {
        Path db = tempDir.resolve("index-and-navigation.db");
        try (HikariDataSource dataSource = new DataSourceConfig().createDataSourceForPath(db.toString());
             Connection indexing = dataSource.getConnection();
             Connection importReader = dataSource.getConnection();
             Connection navigation = dataSource.getConnection()) {
            assertThat(dataSource.getMaximumPoolSize()).isGreaterThanOrEqualTo(3);
            assertThat(pragmaLong(navigation, "cache_size")).isEqualTo(-8_192L);
            assertThat(pragmaLong(indexing, "cache_size")).isEqualTo(-8_192L);
            assertThat(pragmaLong(importReader, "cache_size")).isEqualTo(-8_192L);
        }
    }

    @Test
    void refusesToContinueWhenSqliteReturnsRollbackJournalInsteadOfWal() throws Exception {
        HikariDataSource pool = mock(HikariDataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet response = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("PRAGMA journal_mode=WAL")).thenReturn(response);
        when(response.next()).thenReturn(true);
        when(response.getString(1)).thenReturn("delete");

        assertThatThrownBy(() -> new DataSourceConfig().initializeWalOnce(pool))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot initialize SQLite WAL mode")
                .hasRootCauseInstanceOf(SQLException.class);
        verify(pool, times(3)).getConnection();
    }

    @Test
    void rejectsSqliteWalInitializationWhenEveryAttemptFails() throws Exception {
        HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnection()).thenThrow(new SQLException("read-only catalog"));

        assertThatThrownBy(() -> new DataSourceConfig().initializeWalOnce(pool))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("read-only catalog");
        verify(pool, times(3)).getConnection();
    }

    @Test
    void defaultInteractiveProfileKeepsReleasedProductionTuning() {
        var profile = DataSourceConfig.InteractiveProfile.defaults();
        assertThat(profile.maxPoolSize()).isEqualTo(DataSourceConfig.INTERACTIVE_MAX_POOL_SIZE);
        assertThat(profile.cacheSizeKiB()).isEqualTo(DataSourceConfig.INTERACTIVE_CACHE_SIZE_KIB);
        assertThat(profile.mmapSizeBytes()).isEqualTo(DataSourceConfig.INTERACTIVE_MMAP_SIZE_BYTES);
    }

    @Test
    void explicitInteractiveProfileAppliesPoolAndPragmasWithoutChangingDefaults() throws Exception {
        Path db = tempDir.resolve("profile.db");
        var profile = new DataSourceConfig.InteractiveProfile(2, -8_192, 33_554_432L);
        try (HikariDataSource dataSource = new DataSourceConfig(profile).createDataSourceForPath(db.toString());
             Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(2);
            assertThat(pragmaLong(first, "cache_size")).isEqualTo(-8_192L);
            assertThat(pragmaLong(second, "cache_size")).isEqualTo(-8_192L);
            assertThat(pragmaLong(first, "mmap_size")).isEqualTo(33_554_432L);
            assertThat(pragmaLong(second, "mmap_size")).isEqualTo(33_554_432L);
        }
        var defaults = DataSourceConfig.InteractiveProfile.defaults();
        assertThat(defaults.maxPoolSize()).isEqualTo(3);
        assertThat(defaults.cacheSizeKiB()).isEqualTo(-8_192);
        assertThat(defaults.mmapSizeBytes()).isEqualTo(33_554_432L);
    }

    @Test
    void collectionDataSourceAppliesEveryConnectionLocalPragmaToEveryPhysicalConnection() throws Exception {
        Path db = tempDir.resolve("collection.db");
        try (HikariDataSource dataSource = new DataSourceConfig().createDataSourceForPath(db.toString());
             Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            assertInteractivePragmas(first);
            assertInteractivePragmas(second);
        }
    }

    @Test
    void baseSqlitePropertiesApplyForeignKeysAndBusyTimeoutWithoutInitSqlChaining() throws Exception {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("base.db"));
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(1);
        SqliteConnectionPragmas.applyBase(hikari, 15_000);

        try (HikariDataSource dataSource = new HikariDataSource(hikari);
             Connection connection = dataSource.getConnection()) {
            assertThat(pragmaLong(connection, "foreign_keys")).isEqualTo(1L);
            assertThat(pragmaLong(connection, "busy_timeout")).isEqualTo(15_000L);
        }
    }

    private static void assertInteractivePragmas(Connection connection) throws SQLException {
        assertThat(pragmaLong(connection, "foreign_keys")).isEqualTo(1L);
        assertThat(pragmaLong(connection, "busy_timeout")).isEqualTo(15_000L);
        assertThat(pragmaText(connection, "journal_mode")).isEqualToIgnoringCase("wal");
        assertThat(pragmaLong(connection, "synchronous")).isEqualTo(1L); // NORMAL
        assertThat(pragmaLong(connection, "temp_store")).isEqualTo(1L); // FILE
        assertThat(pragmaLong(connection, "cache_size")).isEqualTo(DataSourceConfig.INTERACTIVE_CACHE_SIZE_KIB);
        assertThat(pragmaLong(connection, "mmap_size")).isEqualTo(DataSourceConfig.INTERACTIVE_MMAP_SIZE_BYTES);
    }

    private static String pragmaText(Connection connection, String pragma) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA " + pragma)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static long pragmaLong(Connection connection, String pragma) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA " + pragma)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
