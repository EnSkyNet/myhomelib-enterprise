package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;
import java.util.Objects;

@Configuration
@Slf4j
public class DataSourceConfig {

    // One lane can be busy with deferred CREATE INDEX while another is in a long
    // read transaction. A third connection keeps normal navigation responsive;
    // 3 x 8 MiB page cache is still below the former 2 x 16 MiB budget.
    static final int INTERACTIVE_MAX_POOL_SIZE = 3;
    // Large INPX imports intentionally keep one SQLite transaction open for atomicity.
    // A 5-minute threshold produced false "Apparent connection leak" warnings for healthy
    // 700k-record imports that later returned the connection normally. Keep leak detection,
    // but place it above the expected full-catalog transaction window.
    private static final long LEAK_DETECTION_THRESHOLD_MS = 1_800_000L; // 30 minutes
    private static final long MAX_LIFETIME_MS = 2_100_000L; // 35 minutes; must stay above leak threshold
    static final int INTERACTIVE_CACHE_SIZE_KIB = -8_192; // 8 MiB per physical SQLite connection.
    static final long INTERACTIVE_MMAP_SIZE_BYTES = 33_554_432L; // 32 MiB virtual file mapping ceiling.

    private final InteractiveProfile interactiveProfile;

    public DataSourceConfig() {
        this(InteractiveProfile.defaults());
    }

    DataSourceConfig(InteractiveProfile interactiveProfile) {
        this.interactiveProfile = Objects.requireNonNull(interactiveProfile, "interactiveProfile");
    }

    /**
     * Створює DataSource для конкретного шляху до БД.
     * Використовується при переключенні колекції.
     */
    public HikariDataSource createDataSourceForPath(String dbPath) {
        if (dbPath == null || dbPath.isEmpty()) {
            throw new IllegalArgumentException("dbPath cannot be null or empty");
        }

        String normalizedPath = dbPath.replace('\\', '/');
        String dbName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1).replace(".db", "");
        HikariDataSource dataSource = new HikariDataSource(baseConfig(
                "jdbc:sqlite:" + dbPath, "HikariPool-" + System.currentTimeMillis() + "-" + dbName));
        initializeWalOnce(dataSource);
        return dataSource;
    }

    private HikariConfig baseConfig(String jdbcUrl, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(interactiveProfile.maxPoolSize());
        config.setMinimumIdle(0); // release idle SQLite page caches when no queries are running
        config.setIdleTimeout(60_000);
        config.setMaxLifetime(MAX_LIFETIME_MS);
        config.setConnectionTimeout(30_000);
        config.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD_MS);
        SqliteConnectionPragmas.applyInteractive(config, 15_000, interactiveProfile.cacheSizeKiB(), interactiveProfile.mmapSizeBytes());
        config.setPoolName(poolName);
        return config;
    }

    record InteractiveProfile(int maxPoolSize, int cacheSizeKiB, long mmapSizeBytes) {
        InteractiveProfile {
            if (maxPoolSize < 1) throw new IllegalArgumentException("maxPoolSize must be >= 1");
            if (cacheSizeKiB >= 0) throw new IllegalArgumentException("cacheSizeKiB must be negative (KiB semantics)");
            if (mmapSizeBytes < 0) throw new IllegalArgumentException("mmapSizeBytes must be >= 0");
        }

        static InteractiveProfile defaults() {
            return new InteractiveProfile(INTERACTIVE_MAX_POOL_SIZE, INTERACTIVE_CACHE_SIZE_KIB, INTERACTIVE_MMAP_SIZE_BYTES);
        }
    }

    /** WAL is persistent database state; setting it on every pooled connection can itself contend for a lock. */
    private void initializeWalOnce(HikariDataSource dataSource) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                return;
            } catch (SQLException error) {
                if (attempt == 3) {
                    log.warn("Не вдалося підтвердити WAL mode для SQLite; продовжуємо з поточним режимом: {}",
                            error.getMessage());
                    return;
                }
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}