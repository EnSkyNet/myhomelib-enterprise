package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.myhomelibcorp.shared.util.AppPaths;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class CollectionManager {

    private final AtomicReference<Collection> currentCollection = new AtomicReference<>();
    private final AtomicReference<DataSource> currentDataSource = new AtomicReference<>();
    private final AtomicReference<JdbcTemplate> currentJdbcTemplate = new AtomicReference<>();
    private final AtomicReference<HikariDataSource> currentHikariDataSource = new AtomicReference<>();
    private final AtomicBoolean isSwitching = new AtomicBoolean(false);

    private final JdbcTemplate metadataJdbcTemplate;
    private final DataSourceConfig dataSourceConfig;

    @Autowired
    public CollectionManager(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate,
            DataSourceConfig dataSourceConfig
    ) {
        this.metadataJdbcTemplate = metadataJdbcTemplate;
        this.dataSourceConfig = dataSourceConfig;
    }

    /**
     * Переключає на іншу колекцію.
     */
    public synchronized void switchToCollection(Collection collection) {
        if (collection == null) {
            log.warn("Спроба переключитися на null колекцію");
            return;
        }

        if (!isSwitching.compareAndSet(false, true)) {
            log.warn("Переключення колекції вже виконується");
            return;
        }

        try {
            log.info("🔄 Початок переключення на колекцію: {}", collection.getName());

            // 1. Закриваємо поточну колекцію
            forceCloseCurrentCollection();

            // 2. Встановлюємо нову колекцію
            currentCollection.set(collection);

            // 3. Створюємо новий DataSource
            String dbPath = getDbPath(collection);
            Path path = Paths.get(dbPath);

            // Створюємо директорію, якщо її немає
            if (path.toAbsolutePath().getParent() != null) {
                java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
            }

            HikariDataSource dataSource = dataSourceConfig.createDataSourceForPath(path.toAbsolutePath().toString());

            // Перевіряємо з'єднання
            try (var conn = dataSource.getConnection()) {
                if (!conn.isValid(1)) {
                    throw new RuntimeException("Невалідне з'єднання з БД");
                }
                log.info("✅ Підключення до БД встановлено: {}", path);
            } catch (SQLException e) {
                throw new RuntimeException("Помилка підключення до БД: " + e.getMessage(), e);
            }

            currentHikariDataSource.set(dataSource);
            currentDataSource.set(dataSource);
            currentJdbcTemplate.set(new JdbcTemplate(dataSource));

            log.info("✅ Переключено на колекцію: {} (БД: {})", collection.getName(), path);

        } catch (Exception e) {
            log.error("❌ Помилка переключення колекції: {}", e.getMessage(), e);
            forceCloseCurrentCollection();
            throw new RuntimeException("Не вдалося переключити колекцію: " + e.getMessage(), e);
        } finally {
            isSwitching.set(false);
        }
    }

    /**
     * Примусове закриття поточної колекції.
     */
    public synchronized void forceCloseCurrentCollection() {
        Collection collection = currentCollection.get();
        if (collection != null) {
            log.debug("🔒 Примусове закриття колекції: {}", collection.getName());
        }

        forceCloseCurrentDataSource();
        currentCollection.set(null);

        if (collection != null) {
            log.debug("✅ Колекцію {} примусово закрито", collection.getName());
        }
    }

    /**
     * Закриває поточний DataSource.
     */
    private synchronized void forceCloseCurrentDataSource() {
        HikariDataSource ds = currentHikariDataSource.getAndSet(null);
        if (ds != null) {
            try {
                ds.close();
                log.debug("✅ HikariDataSource закрито");
            } catch (Exception e) {
                log.warn("⚠️ Помилка закриття HikariDataSource: {}", e.getMessage());
            }
        }
        currentDataSource.set(null);
        currentJdbcTemplate.set(null);
    }

    /**
     * Отримує шлях до БД з колекції.
     */
    private String getDbPath(Collection collection) {
        String dbPath = collection.getDbFile();
        if (dbPath == null || dbPath.isBlank()) {
            dbPath = AppPaths.librariesDir().resolve(collection.getId() + ".db").toString();
        }
        return dbPath;
    }

    // ==================== ПУБЛІЧНІ МЕТОДИ ====================

    public Collection getCurrentCollection() {
        return currentCollection.get();
    }

    public JdbcTemplate getCurrentJdbcTemplate() {
        JdbcTemplate jt = currentJdbcTemplate.get();
        if (jt == null) {
            throw new IllegalStateException("Колекцію не вибрано");
        }
        return jt;
    }

    public DataSource getCurrentDataSource() {
        return currentDataSource.get();
    }

    public boolean hasActiveCollection() {
        return currentCollection.get() != null && currentJdbcTemplate.get() != null;
    }

    public boolean isSwitching() {
        return isSwitching.get();
    }

    /**
     * Закриває поточну колекцію.
     */
    public synchronized void closeCurrentCollection() {
        forceCloseCurrentDataSource();
        currentCollection.set(null);
        log.info("Поточну колекцію закрито");
    }

    /**
     * Перевіряє, чи файл БД заблокований.
     */
    public boolean isDatabaseLocked() {
        DataSource ds = currentDataSource.get();
        if (ds == null) return false;
        try (var conn = ds.getConnection()) {
            return !conn.isValid(1);
        } catch (Exception e) {
            return true;
        }
    }
}