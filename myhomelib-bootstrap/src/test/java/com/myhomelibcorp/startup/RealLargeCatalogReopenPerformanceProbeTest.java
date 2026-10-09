package com.myhomelibcorp.startup;

import com.myhomelibcorp.MyHomeLibApp;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in first-open + second-open acceptance over a copy of a large real catalogue. */
class RealLargeCatalogReopenPerformanceProbeTest {
    @TempDir Path temp;

    @Test
    @EnabledIfSystemProperty(named = "mhl.real.reopen.db", matches = ".+")
    void secondOpenReusesSealedLuceneIndexAndPreservesCatalogue() throws Exception {
        Path sourceDb = Path.of(System.getProperty("mhl.real.reopen.db")).toAbsolutePath().normalize();
        assertThat(sourceDb).isRegularFile();
        Path workingDb = temp.resolve("large-catalog.db");
        Files.copy(sourceDb, workingDb);
        Path dataDir = temp.resolve("data");
        Files.createDirectories(dataDir);

        String oldDataDir = System.getProperty("myhomelib.dataDir");
        String oldMetaDb = System.getProperty("app.metadata.db-path");
        String oldHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("myhomelib.dataDir", dataDir.toString());
            System.setProperty("app.metadata.db-path", dataDir.resolve("meta.db").toString());
            System.setProperty("java.awt.headless", "true");

            long firstMs;
            long firstBooks;
            try (var context = context()) {
                CollectionRepository collections = context.getBean(CollectionRepository.class);
                Collection stored = collections.save(new Collection(
                        "real-reopen-profile", "Real reopen profile", temp.resolve("library-root"),
                        workingDb.toString(), 0, null, null, null, null));
                long started = System.nanoTime();
                StartupReport first = context.getBean(StartupOrchestrator.class).run();
                firstMs = elapsedMs(started);
                firstBooks = context.getBean(com.myhomelibcorp.infrastructure.collection.CollectionManager.class)
                        .getCurrentJdbcTemplate().queryForObject("SELECT COUNT(*) FROM books", Long.class);
                assertThat(first.activeCollection().getId()).isEqualTo(stored.getId());
                waitUntilFresh(context.getBean(SearchIndexLifecycle.class), Duration.ofSeconds(90));
                // Match the real application shutdown order: Lucene commit/close -> SQLite close/checkpoint -> freshness seal.
                context.getBean(CollectionLifecycleService.class).closeCollection();
            }

            long secondMs;
            long secondBooks;
            StartupReport second;
            boolean freshImmediately;
            try (var context = context()) {
                long started = System.nanoTime();
                second = context.getBean(StartupOrchestrator.class).run();
                secondMs = elapsedMs(started);
                secondBooks = context.getBean(com.myhomelibcorp.infrastructure.collection.CollectionManager.class)
                        .getCurrentJdbcTemplate().queryForObject("SELECT COUNT(*) FROM books", Long.class);
                freshImmediately = context.getBean(SearchIndexLifecycle.class).currentHealth().fresh();
                context.getBean(CollectionLifecycleService.class).closeCollection();
            }

            StartupTaskOutcome search = second.outcomes().stream()
                    .filter(outcome -> outcome.taskId().equals("SearchStartupTask"))
                    .findFirst().orElseThrow();
            System.out.printf(java.util.Locale.ROOT,
                    "REAL_REOPEN firstStartupMs=%d secondStartupMs=%d firstBooks=%d secondBooks=%d searchStatus=%s searchDetail='%s' freshImmediately=%s%n",
                    firstMs, secondMs, firstBooks, secondBooks, search.status(), search.detail(), freshImmediately);
            assertThat(firstBooks).isGreaterThan(500_000L);
            assertThat(secondBooks).isEqualTo(firstBooks);
            assertThat(search.status()).isEqualTo(StartupTaskOutcome.Status.SKIPPED);
            assertThat(search.detail()).contains("reusable");
            assertThat(freshImmediately).isTrue();
        } finally {
            restore("myhomelib.dataDir", oldDataDir);
            restore("app.metadata.db-path", oldMetaDb);
            restore("java.awt.headless", oldHeadless);
        }
    }

    private org.springframework.context.ConfigurableApplicationContext context() {
        return new SpringApplicationBuilder(MyHomeLibApp.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "spring.main.lazy-initialization=false", "logging.level.root=WARN")
                .run();
    }

    private static void waitUntilFresh(SearchIndexLifecycle lifecycle, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (lifecycle.currentHealth().fresh()) return;
            java.util.concurrent.locks.LockSupport.parkNanos(250_000_000L);
        }
        throw new AssertionError("Lucene index did not become fresh within " + timeout);
    }

    private static long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000L; }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
