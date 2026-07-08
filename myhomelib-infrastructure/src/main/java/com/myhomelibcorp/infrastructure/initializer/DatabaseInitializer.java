package com.myhomelibcorp.infrastructure.initializer;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteAuthorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseInitializer {

    private final CollectionManager collectionManager;
    private final SqliteAuthorRepository authorRepository;

    public void initializeCurrentCollection() {
        log.info("=== DatabaseInitializer.initializeCurrentCollection() START ===");
        if (!collectionManager.hasActiveCollection()) {
            log.warn("Колекцію не вибрано, пропускаємо ініціалізацію");
            return;
        }

        DataSource dataSource = collectionManager.getCurrentDataSource();
        if (dataSource == null) {
            log.error("DataSource для поточної колекції дорівнює null");
            throw new IllegalStateException("DataSource не доступний");
        }

        log.info("Ініціалізація БД для колекції: {}", collectionManager.getCurrentCollection().getName());

        try {
            String url = dataSource.getConnection().getMetaData().getURL();
            log.info("Підключення до БД: {}", url);
        } catch (Exception e) {
            log.warn("Не вдалося отримати URL БД", e);
        }

        // ---- 1. Запуск міграцій Flyway ----
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
            MigrateResult result = flyway.migrate();
            log.info("Flyway міграції виконано. Застосовано {} міграцій.", result.migrationsExecuted);
        } catch (Exception e) {
            log.error("Помилка міграції Flyway для бібліотечної БД", e);
            throw new RuntimeException("Не вдалося виконати міграцію БД колекції", e);
        }

        // ---- 2. Додаткові міграції ----
        try {
            authorRepository.addSearchNameColumnIfNotExists();
            authorRepository.updateSearchNamesForAllAuthors();
            log.info("Додаткові міграції виконано успішно");
        } catch (Exception e) {
            log.error("Помилка додаткових міграцій", e);
        }

        log.info("Ініціалізацію БД для колекції завершено");
    }
}