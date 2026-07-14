package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class CollectionManager {

    private final AtomicReference<Collection> currentCollection = new AtomicReference<>();
    private final AtomicReference<DataSource> currentDataSource = new AtomicReference<>();
    private final AtomicReference<JdbcTemplate> currentJdbcTemplate = new AtomicReference<>();

    private final JdbcTemplate metadataJdbcTemplate;

    @Autowired
    public CollectionManager(@Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate) {
        this.metadataJdbcTemplate = metadataJdbcTemplate;
    }

    public synchronized void switchToCollection(Collection collection) {
        if (collection == null) {
            log.warn("Спроба переключитися на null колекцію");
            return;
        }

        DataSource oldDs = currentDataSource.getAndSet(null);
        if (oldDs instanceof HikariDataSource) {
            try {
                ((HikariDataSource) oldDs).close();
                log.info("Старий DataSource закрито");
            } catch (Exception e) {
                log.warn("Помилка закриття старого DataSource", e);
            }
        }

        currentCollection.set(collection);

        String dbPath = collection.getDbFile();
        if (dbPath == null || dbPath.isBlank()) {
            String defaultPath = System.getProperty("user.home") + "/.myhomelibcorp/libraries/" + collection.getId() + ".db";
            dbPath = defaultPath;
        }

        Path path = Paths.get(dbPath);
        path.getParent().toFile().mkdirs();

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + path.toAbsolutePath());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(10);
        dataSource.setConnectionTimeout(30000);
        dataSource.setIdleTimeout(600000);
        dataSource.setMaxLifetime(1800000);

        currentDataSource.set(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        currentJdbcTemplate.set(jdbcTemplate);

        try {
            jdbcTemplate.execute("PRAGMA journal_mode=WAL");
            jdbcTemplate.execute("PRAGMA synchronous=NORMAL");
            jdbcTemplate.execute("PRAGMA temp_store=MEMORY");
            jdbcTemplate.execute("PRAGMA cache_size=-500000");
            log.info("PRAGMA встановлено для колекції: {}", collection.getName());
        } catch (Exception e) {
            log.warn("Не вдалося встановити PRAGMA для колекції: {}", e.getMessage());
        }

        log.info("Переключено на колекцію: {} (БД: {})", collection.getName(), path);
    }

    public Collection getCurrentCollection() {
        return currentCollection.get();
    }

    public JdbcTemplate getCurrentJdbcTemplate() {
        JdbcTemplate jt = currentJdbcTemplate.get();
        if (jt == null) {
            throw new IllegalStateException("Колекцію не вибрано. Спочатку виберіть або створіть колекцію.");
        }
        return jt;
    }

    public DataSource getCurrentDataSource() {
        return currentDataSource.get();
    }

    public boolean hasActiveCollection() {
        return currentCollection.get() != null && currentJdbcTemplate.get() != null;
    }

    public synchronized void closeCurrentCollection() {
        DataSource ds = currentDataSource.getAndSet(null);
        if (ds instanceof HikariDataSource) {
            try {
                ((HikariDataSource) ds).close();
            } catch (Exception e) {
                log.warn("Помилка закриття DataSource", e);
            }
        }
        currentCollection.set(null);
        currentJdbcTemplate.set(null);
        log.info("Поточну колекцію закрито");
    }
}