package com.myhomelibcorp.startup;

import com.myhomelibcorp.MyHomeLibApp;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in startup-task profile against a copy of a real large catalogue DB. */
class RealLargeCatalogStartupPerformanceProbeTest {
    @TempDir Path temp;

    @Test
    @EnabledIfSystemProperty(named = "mhl.real.startup.db", matches = ".+")
    void profilesSpringAndStartupTasksAgainstLargeCatalogue() throws Exception {
        Path sourceDb = Path.of(System.getProperty("mhl.real.startup.db")).toAbsolutePath().normalize();
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

            long contextStarted = System.nanoTime();
            try (var context = new SpringApplicationBuilder(MyHomeLibApp.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "spring.main.lazy-initialization=false", "logging.level.root=WARN")
                    .run()) {
                long contextMs = elapsedMs(contextStarted);
                CollectionRepository collections = context.getBean(CollectionRepository.class);
                Collection stored = collections.save(new Collection(
                        "real-startup-profile", "Real 707k profile", temp.resolve("library-root"),
                        workingDb.toString(), 0, null, null, null, null));
                assertThat(stored.getDbFile()).isEqualTo(workingDb.toString());

                StartupOrchestrator orchestrator = context.getBean(StartupOrchestrator.class);
                long startupStarted = System.nanoTime();
                StartupReport report = orchestrator.run();
                long startupMs = elapsedMs(startupStarted);
                long books = context.getBean(com.myhomelibcorp.infrastructure.collection.CollectionManager.class)
                        .getCurrentJdbcTemplate().queryForObject("SELECT COUNT(*) FROM books", Long.class);
                System.out.printf("REAL_STARTUP_RESULT contextMs=%d startupTasksMs=%d books=%d degraded=%s outcomes=%s%n",
                        contextMs, startupMs, books, report.degraded(), report.outcomes());
                assertThat(books).isGreaterThan(500_000L);
                assertThat(report.activeCollection().getId()).isEqualTo(stored.getId());
            }
        } finally {
            restore("myhomelib.dataDir", oldDataDir);
            restore("app.metadata.db-path", oldMetaDb);
            restore("java.awt.headless", oldHeadless);
        }
    }

    private static long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000L; }
    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
