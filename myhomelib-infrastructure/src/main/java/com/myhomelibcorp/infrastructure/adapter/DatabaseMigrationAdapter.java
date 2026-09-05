package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteAuthorRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.KeywordIndexBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseMigrationAdapter implements DatabaseMigrationPort {

    private final CollectionManager collectionManager;
    private final SqliteAuthorRepository authorRepository;
    private final KeywordIndexBackfillService keywordIndexBackfillService;
    private final LegacyMigrationDataGuard legacyMigrationDataGuard;

    @Override
    public int migrateCurrentCollection() {
        if (!collectionManager.hasActiveCollection()) {
            log.warn("Немає активної колекції для міграції");
            return 0;
        }

        DataSource dataSource = collectionManager.getCurrentDataSource();
        if (dataSource == null) {
            log.warn("DataSource недоступний для міграції");
            return 0;
        }

        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();

            var current = flyway.info().current();
            String currentVersion = current == null || current.getVersion() == null
                    ? null : current.getVersion().getVersion();
            legacyMigrationDataGuard.captureBeforeMigrate(dataSource, currentVersion);

            MigrateResult result = flyway.migrate();
            // This also resumes an interrupted prior run: guard tables are durable and are only
            // removed after the final schema has been verified to contain all protected data.
            legacyMigrationDataGuard.restoreAfterMigrate(dataSource);
            authorRepository.normalizeSearchNamesIfNeeded();
            keywordIndexBackfillService.backfillIfNeeded();

            if (result.migrationsExecuted > 0) {
                log.info("✅ Виконано {} міграцій", result.migrationsExecuted);
                result.migrations.forEach(m ->
                        log.info("  - {}: {}", m.version, m.description)
                );
            } else {
                log.debug("Нових міграцій немає");
            }

            return result.migrationsExecuted;

        } catch (Exception e) {
            log.error("❌ Помилка міграції Flyway", e);
            throw new RuntimeException("Не вдалося виконати міграцію БД", e);
        }
    }

    @Override
    public boolean isMigrationNeeded() {
        if (!collectionManager.hasActiveCollection()) {
            return false;
        }

        DataSource dataSource = collectionManager.getCurrentDataSource();
        if (dataSource == null) {
            return false;
        }

        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();

            return flyway.info().pending().length > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося визначити стан міграцій БД", e);
        }
    }
}