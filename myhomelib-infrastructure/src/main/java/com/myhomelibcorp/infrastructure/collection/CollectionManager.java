package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.application.event.CollectionOpenedEvent;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final ApplicationEventPublisher eventPublisher;
    private final DataSourceConfig dataSourceConfig;

    @Autowired
    public CollectionManager(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            DataSourceConfig dataSourceConfig
    ) {
        this.metadataJdbcTemplate = metadataJdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.dataSourceConfig = dataSourceConfig;
    }

    /**
     * Переключає на іншу колекцію з повним очищенням ресурсів.
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

            // 1. Зберігаємо стан поточної колекції
            saveCurrentState();

            // 2. Закриваємо поточну колекцію з примусовим звільненням
            forceCloseCurrentCollection();

            // 3. Встановлюємо нову колекцію
            currentCollection.set(collection);

            // 4. Створюємо новий DataSource
            String dbPath = getDbPath(collection);
            Path path = Paths.get(dbPath);

            // Створюємо директорію, якщо її немає
            try {
                path.getParent().toFile().mkdirs();
                log.info("📁 Створено директорію для БД: {}", path.getParent());
            } catch (Exception e) {
                log.warn("Не вдалося створити директорію для БД: {}", e.getMessage());
            }

            // Створюємо HikariDataSource через конфігурацію
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

            // 5. Виконуємо міграції Flyway
            runFlywayMigrations(dataSource);

            // 6. Оновлюємо метадані
            updateCollectionMetadata(collection);

            // 7. Публікуємо подію про відкриття колекції
            eventPublisher.publishEvent(new CollectionOpenedEvent(collection));

            log.info("✅ Переключено на колекцію: {} (БД: {})", collection.getName(), path);

        } catch (Exception e) {
            log.error("❌ Помилка переключення колекції: {}", e.getMessage(), e);
            // Скидаємо стан при помилці
            forceCloseCurrentCollection();
            throw new RuntimeException("Не вдалося переключити колекцію: " + e.getMessage(), e);
        } finally {
            isSwitching.set(false);
        }
    }

    /**
     * Зберігає стан поточної колекції перед переключенням.
     */
    private void saveCurrentState() {
        Collection current = currentCollection.get();
        if (current == null) {
            return;
        }

        try {
            // Зберігаємо останню відкриту книгу, позицію читання тощо
            log.debug("💾 Збереження стану колекції: {}", current.getName());
            // Тут можна додати збереження стану
        } catch (Exception e) {
            log.warn("Не вдалося зберегти стан колекції: {}", e.getMessage());
        }
    }

    /**
     * Закриває поточний DataSource з примусовим звільненням.
     */
    private synchronized void forceCloseCurrentDataSource() {
        HikariDataSource ds = currentHikariDataSource.getAndSet(null);
        if (ds != null) {
            try {
                // Закриваємо всі з'єднання
                ds.close();
                log.info("✅ HikariDataSource закрито");
            } catch (Exception e) {
                log.warn("⚠️ Помилка закриття HikariDataSource: {}", e.getMessage());
            }
        }
        currentDataSource.set(null);
        currentJdbcTemplate.set(null);
    }

    /**
     * Примусове закриття поточної колекції.
     */
    public synchronized void forceCloseCurrentCollection() {
        Collection collection = currentCollection.get();
        if (collection != null) {
            log.info("🔒 Примусове закриття колекції: {}", collection.getName());
        }

        // Закриваємо DataSource
        forceCloseCurrentDataSource();

        // Очищаємо посилання
        currentCollection.set(null);

        // Примусове звільнення пам'яті
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (collection != null) {
            log.info("✅ Колекцію {} примусово закрито", collection.getName());
        }
    }

    /**
     * Виконує міграції Flyway для нової колекції.
     */
    private void runFlywayMigrations(DataSource dataSource) {
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();

            var result = flyway.migrate();
            log.info("✅ Flyway міграції виконано. Застосовано {} міграцій.",
                    result.migrationsExecuted);
        } catch (Exception e) {
            log.error("❌ Помилка міграції Flyway", e);
            throw new RuntimeException("Не вдалося виконати міграцію БД", e);
        }
    }

    /**
     * Оновлює метадані колекції.
     */
    private void updateCollectionMetadata(Collection collection) {
        try {
            // Оновлюємо час останнього відкриття
            String sql = """
                    UPDATE collections 
                    SET last_opened = CURRENT_TIMESTAMP 
                    WHERE id = ?
                    """;
            metadataJdbcTemplate.update(sql, collection.getId());
            log.debug("✅ Метадані колекції оновлено");
        } catch (Exception e) {
            log.warn("Не вдалося оновити метадані колекції: {}", e.getMessage());
        }
    }

    /**
     * Отримує шлях до БД з колекції або створює стандартний.
     */
    private String getDbPath(Collection collection) {
        String dbPath = collection.getDbFile();
        if (dbPath == null || dbPath.isBlank()) {
            dbPath = System.getProperty("user.home") +
                    "/.myhomelibcorp/libraries/" +
                    collection.getId() + ".db";
            log.info("ℹ️ dbFile не вказано, використовуємо стандартний шлях: {}", dbPath);
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

    public boolean isSwitching() {
        return isSwitching.get();
    }

    /**
     * Закриває поточну колекцію (без примусового звільнення).
     */
    public synchronized void closeCurrentCollection() {
        Collection collection = currentCollection.get();
        if (collection != null) {
            log.info("Закриття колекції: {}", collection.getName());
        }
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

    /**
     * Отримує розмір БД у байтах.
     */
    public long getDatabaseSize() {
        Collection collection = currentCollection.get();
        if (collection == null) return 0;

        String dbPath = getDbPath(collection);
        try {
            return java.nio.file.Files.size(Paths.get(dbPath));
        } catch (Exception e) {
            return 0;
        }
    }
}