package com.myhomelibcorp.infrastructure.initializer;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteAuthorRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseInitializer {

    private final CollectionManager collectionManager;
    private final SqliteAuthorRepository authorRepository;
    private final SqliteSeriesRepository seriesRepository;

    private final AtomicBoolean initializing = new AtomicBoolean(false);

    public void initializeCurrentCollection() {
        // Запобігаємо паралельним викликам
        if (!initializing.compareAndSet(false, true)) {
            log.debug("Ініціалізація вже виконується, пропускаємо");
            return;
        }

        try {
            log.info("=== DatabaseInitializer.initializeCurrentCollection() START ===");

            // ПЕРЕВІРКА: чи активна колекція
            if (!collectionManager.hasActiveCollection()) {
                log.warn("Колекцію не вибрано, пропускаємо ініціалізацію");
                return;
            }

            // ПЕРЕВІРКА: чи доступний DataSource
            DataSource dataSource = collectionManager.getCurrentDataSource();
            if (dataSource == null) {
                log.error("DataSource для поточної колекції дорівнює null");
                throw new IllegalStateException("DataSource недоступний");
            }

            // ПЕРЕВІРКА: чи працює з'єднання
            try (var conn = dataSource.getConnection()) {
                if (!conn.isValid(1)) {
                    log.error("З'єднання з БД невалідне");
                    throw new IllegalStateException("Невалідне з'єднання з БД");
                }
                String url = conn.getMetaData().getURL();
                log.info("✅ Підключення до БД: {}", url);
            } catch (Exception e) {
                log.error("Помилка перевірки з'єднання з БД", e);
                throw new IllegalStateException("Не вдалося підключитися до БД", e);
            }

            log.info("Ініціалізація БД для колекції: {}",
                    collectionManager.getCurrentCollection() != null ?
                            collectionManager.getCurrentCollection().getName() : "unknown");

            // ---- 1. Запуск міграцій Flyway ----
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(true)
                        .load();
                MigrateResult result = flyway.migrate();
                log.info("✅ Flyway міграції виконано. Застосовано {} міграцій.", result.migrationsExecuted);
            } catch (Exception e) {
                log.error("❌ Помилка міграції Flyway для бібліотечної БД", e);
                throw new RuntimeException("Не вдалося виконати міграцію БД колекції", e);
            }

            // ---- 2. Додаткові міграції ----
            try {
                authorRepository.addSearchNameColumnIfNotExists();
                authorRepository.updateSearchNamesForAllAuthors();
                log.info("✅ Додаткові міграції виконано успішно");
            } catch (Exception e) {
                log.error("❌ Помилка додаткових міграцій", e);
            }

            // ---- 3. Синхронізація серій ----
            try {
                seriesRepository.syncSeriesFromBooks();
                log.info("✅ Синхронізацію серій виконано");
            } catch (Exception e) {
                log.error("❌ Помилка синхронізації серій", e);
            }

            log.info("✅ Ініціалізацію БД для колекції завершено");

        } finally {
            initializing.set(false);
        }
    }
}