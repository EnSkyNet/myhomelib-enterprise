package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSourceConfig {

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
        config.setLeakDetectionThreshold(10000);

        config.setConnectionInitSql(
                "PRAGMA foreign_keys=ON; " +
                "PRAGMA journal_mode=WAL; " +
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
        config.setLeakDetectionThreshold(10000);

        config.setConnectionInitSql(
                "PRAGMA foreign_keys=ON; " +
                "PRAGMA journal_mode=WAL; " +
                        "PRAGMA synchronous=NORMAL; " +
                        "PRAGMA temp_store=MEMORY; " +
                        "PRAGMA cache_size=-32768; " +
                        "PRAGMA mmap_size=67108864;"
        );

        String poolName = "HikariPool-" + System.currentTimeMillis() + "-" +
                dbPath.substring(dbPath.lastIndexOf('/') + 1).replace(".db", "");
        config.setPoolName(poolName);

        return new HikariDataSource(config);
    }
}