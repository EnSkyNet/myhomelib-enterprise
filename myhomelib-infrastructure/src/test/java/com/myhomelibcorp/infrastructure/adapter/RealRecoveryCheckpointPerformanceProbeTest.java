package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in benchmark for the crash-safe pre-update SQLite checkpoint on a production-sized catalogue. */
class RealRecoveryCheckpointPerformanceProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.db", matches = ".+")
    void createsRecoveryCheckpointFromRealDatabase() throws Exception {
        Path db = Path.of(System.getProperty("mhl.real.db")).toAbsolutePath().normalize();
        Path checkpoint = Path.of(System.getProperty("mhl.real.checkpoint", "target/real-recovery-checkpoint.db"))
                .toAbsolutePath().normalize();
        assertThat(db).isRegularFile();
        Files.createDirectories(checkpoint.getParent());
        Files.deleteIfExists(checkpoint);

        try (HikariDataSource ds = new DataSourceConfig().createDataSourceForPath(db.toString())) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.hasActiveCollection()).thenReturn(true);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            CollectionBackupAdapter adapter = new CollectionBackupAdapter(manager);
            Collection collection = mock(Collection.class);

            long started = System.nanoTime();
            adapter.createRecoveryCheckpoint(collection, checkpoint);
            long wallMs = (System.nanoTime() - started) / 1_000_000L;

            long sourceBytes = Files.size(db);
            long checkpointBytes = Files.size(checkpoint);
            System.out.printf("REAL_CHECKPOINT_RESULT wallMs=%d sourceBytes=%d checkpointBytes=%d mbPerSec=%.2f%n",
                    wallMs, sourceBytes, checkpointBytes,
                    wallMs == 0 ? 0.0 : (sourceBytes / 1024.0 / 1024.0) / (wallMs / 1000.0));
            assertThat(checkpointBytes).isGreaterThan(100L);
            assertThat(checkpointBytes).isLessThanOrEqualTo(sourceBytes + 64L * 1024L * 1024L);
        }
    }
}
