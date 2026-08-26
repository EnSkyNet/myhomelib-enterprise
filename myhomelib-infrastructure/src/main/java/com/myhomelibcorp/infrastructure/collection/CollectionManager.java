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

        HikariDataSource candidate = null;
        try {
            log.info("🔄 Початок переключення на колекцію: {}", collection.getName());

            // Спочатку повністю відкриваємо і перевіряємо нову БД. Стару колекцію
            // не закриваємо, доки не впевнимося, що переключення можливе.
            String dbPath = getDbPath(collection);
            Path path = Paths.get(dbPath);

            // Створюємо директорію, якщо її немає
            if (path.toAbsolutePath().getParent() != null) {
                java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
            }

            candidate = dataSourceConfig.createDataSourceForPath(path.toAbsolutePath().toString());

            // Перевіряємо з'єднання
            try (var conn = candidate.getConnection()) {
                if (!conn.isValid(1)) {
                    throw new RuntimeException("Невалідне з'єднання з БД");
                }
                log.info("✅ Підключення до БД встановлено: {}", path);
            } catch (SQLException e) {
                throw new RuntimeException("Помилка підключення до БД: " + e.getMessage(), e);
            }

            HikariDataSource previous = currentHikariDataSource.getAndSet(candidate);
            currentDataSource.set(candidate);
            currentJdbcTemplate.set(new JdbcTemplate(candidate));
            currentCollection.set(collection);
            candidate = null; // ownership transferred to currentHikariDataSource

            if (previous != null) {
                try {
                    previous.close();
                } catch (Exception e) {
                    log.warn("⚠️ Не вдалося коректно закрити попередній DataSource: {}", e.getMessage());
                }
            }

            log.info("✅ Переключено на колекцію: {} (БД: {})", collection.getName(), path);

        } catch (Exception e) {
            log.error("❌ Помилка переключення колекції: {}", e.getMessage(), e);
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Exception closeError) {
                    log.debug("Не вдалося закрити невикористаний DataSource: {}", closeError.getMessage());
                }
            }
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

    /**
     * Замінює лише metadata активної колекції, не чіпаючи вже відкриту БД.
     */
    public synchronized void updateCurrentCollection(Collection collection) {
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) {
            throw new IllegalArgumentException("Колекція для оновлення не задана");
        }
        Collection current = currentCollection.get();
        if (current == null || current.getId() == null || !current.getId().equals(collection.getId())) {
            throw new IllegalStateException("Можна оновити metadata лише активної колекції");
        }
        currentCollection.set(collection);
        log.debug("Metadata активної колекції оновлено: {}", collection.getName());
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