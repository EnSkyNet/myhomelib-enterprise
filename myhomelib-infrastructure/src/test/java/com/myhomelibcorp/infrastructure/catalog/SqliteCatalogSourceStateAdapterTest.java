package com.myhomelibcorp.infrastructure.catalog;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.persistence.sqlite.TestCollectionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteCatalogSourceStateAdapterTest {
    private HikariDataSource dataSource;
    private SqliteCatalogSourceStateAdapter stateAdapter;
    private SqliteCatalogUpdateTrackingAdapter trackingAdapter;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:file:source-state-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(2);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentCollection(new Collection("c1", "Online", Path.of("."), null, 1,
                null, null, "https://example.test", null));
        manager.setCurrentDataSource(dataSource);
        manager.setCurrentJdbcTemplate(jdbc);
        stateAdapter = new SqliteCatalogSourceStateAdapter(manager);
        trackingAdapter = new SqliteCatalogUpdateTrackingAdapter(manager,
                new com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBusyRetryExecutor());
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void preflightPlaceholderRemainsTheFirstRealSyncBaseline() {
        String sourceKey = "remote-collection:c1";
        String realFingerprint = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

        stateAdapter.recordChecked(sourceKey, "https://example.test/full.inpx", "INPX", "2026-09-24");
        var session = trackingAdapter.beginSync(sourceKey, "https://example.test/full.inpx", realFingerprint);

        assertThat(session.initialBaseline()).isTrue();
        assertThat(session.sourceChanged()).isTrue();
        assertThat(session.sourceRevision()).isEqualTo(1L);
        assertThat(stateAdapter.matchesAppliedFingerprint(sourceKey, realFingerprint)).isTrue();
        assertThat(stateAdapter.matchesAppliedFingerprint(
                sourceKey, com.myhomelibcorp.application.catalog.CatalogSourceIdentity.UNAPPLIED_FINGERPRINT)).isFalse();
    }

    @Test
    void appliedFingerprintIsIndependentFromLastDownloadedSha() {
        String sourceKey = "remote-collection:c1";
        String applied = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String downloadedButUnapplied = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        trackingAdapter.beginSync(sourceKey, "https://example.test/full.inpx", applied);
        stateAdapter.recordDownloaded(sourceKey, "etag", "last-modified", downloadedButUnapplied, "inpx");

        assertThat(stateAdapter.matchesAppliedFingerprint(sourceKey, applied)).isTrue();
        assertThat(stateAdapter.matchesAppliedFingerprint(sourceKey, downloadedButUnapplied)).isFalse();
        assertThat(stateAdapter.matchesAppliedFingerprint(sourceKey, " ")).isFalse();
    }
}
