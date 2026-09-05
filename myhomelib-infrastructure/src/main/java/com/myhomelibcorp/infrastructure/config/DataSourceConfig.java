package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;

@Configuration
@Slf4j
public class DataSourceConfig {

    // Large INPX imports intentionally keep one SQLite transaction open for atomicity.
    // A 5-minute threshold produced false "Apparent connection leak" warnings for healthy
    // 700k-record imports that later returned the connection normally. Keep leak detection,
    // but place it above the expected full-catalog transaction window.
    private static final long LEAK_DETECTION_THRESHOLD_MS = 1_800_000L; // 30 minutes

    /**
     * Створює базовий DataSource для мета-БД (колекції).
     * Використовується як fallback.
     */
    @Bean(name = "defaultDataSource")
    public HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite::memory:");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setConnectionTimeout(30000);
        config.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD_MS);

        config.setConnectionInitSql(
                "PRAGMA foreign_keys=ON; " +
                "PRAGMA busy_timeout=15000; " +
                        "PRAGMA synchronous=NORMAL; " +
                        "PRAGMA temp_store=MEMORY; " +
                        "PRAGMA cache_size=-32768; " +
                        "PRAGMA mmap_size=67108864;"
        );

        config.setPoolName("HikariPool-MyHomeLib");

        return new HikariDataSource(config);
    }

    /**
     * Створює DataSource для конкретного шляху до БД.
     * Використовується при переключенні колекції.
     */
    public HikariDataSource createDataSourceForPath(String dbPath) {
        if (dbPath == null || dbPath.isEmpty()) {
            throw new IllegalArgumentException("dbPath cannot be null or empty");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setConnectionTimeout(30000);
        config.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD_MS);

        config.setConnectionInitSql(
                "PRAGMA foreign_keys=ON; " +
                "PRAGMA busy_timeout=15000; " +
                        "PRAGMA synchronous=NORMAL; " +
                        "PRAGMA temp_store=MEMORY; " +
                        "PRAGMA cache_size=-32768; " +
                        "PRAGMA mmap_size=67108864;"
        );

        String normalizedPath = dbPath.replace('\\', '/');
        String dbName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1).replace(".db", "");
        config.setPoolName("HikariPool-" + System.currentTimeMillis() + "-" + dbName);

        HikariDataSource dataSource = new HikariDataSource(config);
        initializeWalOnce(dataSource);
        return dataSource;
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