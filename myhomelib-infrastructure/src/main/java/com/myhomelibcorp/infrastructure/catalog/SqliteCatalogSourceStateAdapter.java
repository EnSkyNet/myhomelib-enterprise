package com.myhomelibcorp.infrastructure.catalog;

import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.catalog.CatalogSourceState;
import com.myhomelibcorp.application.port.out.catalog.CatalogSourceStatePort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SqliteCatalogSourceStateAdapter implements CatalogSourceStatePort {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final CollectionManager collectionManager;

    private JdbcTemplate jdbc() { return collectionManager.getCurrentJdbcTemplate(); }

    @Override
    public CatalogSourceState get(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) return CatalogSourceState.empty("");
        List<CatalogSourceState> rows = jdbc().query("""
                SELECT source_key, COALESCE(source_location,''), COALESCE(profile_type,''),
                       COALESCE(applied_version,''), COALESCE(remote_version,''), COALESCE(etag,''),
                       COALESCE(last_modified,''), COALESCE(sha256,''), COALESCE(dataset_schema,''),
                       COALESCE(last_error,'')
                  FROM catalog_sources WHERE source_key = ?
                """, (rs, rowNum) -> new CatalogSourceState(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)), sourceKey.trim());
        return rows.isEmpty() ? CatalogSourceState.empty(sourceKey.trim()) : rows.getFirst();
    }

    @Override
    public void recordChecked(String sourceKey, String sourceLocation, String profileType, String remoteVersion) {
        ensure(sourceKey, sourceLocation);
        jdbc().update("""
                UPDATE catalog_sources
                   SET source_location = ?, profile_type = ?, remote_version = ?, last_checked_at = ?,
                       last_error = NULL, last_error_at = NULL
                 WHERE source_key = ?
                """, safe(sourceLocation), safe(profileType), nullable(remoteVersion), now(), sourceKey.trim());
    }

    @Override
    public void recordDownloaded(String sourceKey, String etag, String lastModified, String sha256, String datasetSchema) {
        ensure(sourceKey, "");
        jdbc().update("""
                UPDATE catalog_sources
                   SET etag = ?, last_modified = ?, sha256 = ?, dataset_schema = ?, last_downloaded_at = ?
                 WHERE source_key = ?
                """, nullable(etag), nullable(lastModified), nullable(sha256), nullable(datasetSchema), now(), sourceKey.trim());
    }

    @Override
    public boolean matchesAppliedFingerprint(String sourceKey, String sha256) {
        if (sourceKey == null || sourceKey.isBlank() || sha256 == null || sha256.isBlank()) return false;
        String normalizedSha = sha256.trim();
        Long count = jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM catalog_sources
                 WHERE source_key = ? AND source_fingerprint = ?
                """, Long.class, sourceKey.trim(), normalizedSha);
        return count != null && count > 0;
    }

    @Override
    public void recordApplied(String sourceKey, String appliedVersion) {
        ensure(sourceKey, "");
        jdbc().update("""
                UPDATE catalog_sources
                   SET applied_version = ?, last_applied_at = ?, last_synced_at = ?,
                       last_error = NULL, last_error_at = NULL
                 WHERE source_key = ?
                """, nullable(appliedVersion), now(), now(), sourceKey.trim());
    }

    @Override
    public void recordFailure(String sourceKey, String message) {
        if (sourceKey == null || sourceKey.isBlank()) return;
        ensure(sourceKey, "");
        String safeMessage = message == null ? "Unknown catalog update error" : message;
        if (safeMessage.length() > 4000) safeMessage = safeMessage.substring(0, 4000);
        jdbc().update("UPDATE catalog_sources SET last_error = ?, last_error_at = ? WHERE source_key = ?",
                safeMessage, now(), sourceKey.trim());
    }

    private void ensure(String sourceKey, String sourceLocation) {
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey is required");
        String key = sourceKey.trim();
        jdbc().update("""
                INSERT OR IGNORE INTO catalog_sources(
                    source_id, source_key, source_location, source_revision, source_fingerprint,
                    first_seen_at, last_synced_at
                ) VALUES (?, ?, ?, 1, ?, ?, ?)
                """, CatalogSourceIdentity.stableId(key), key, safe(sourceLocation), "sync-state:unapplied", now(), now());
    }

    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String now() { return LocalDateTime.now().format(TS); }
}
