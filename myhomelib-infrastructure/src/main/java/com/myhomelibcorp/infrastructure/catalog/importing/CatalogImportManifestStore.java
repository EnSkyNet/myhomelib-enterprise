package com.myhomelibcorp.infrastructure.catalog.importing;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.shared.util.Sha256Support;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns catalog-source identity, manifest compatibility and source revision metadata.
 *
 * <p>Keeping this policy outside the streaming batch writer prevents parser/import changes from
 * silently drifting away from the manifest version contract.</p>
 */
final class CatalogImportManifestStore {
    private static final String PARSER_VERSION = "catalog-v7.1";
    private static final String MANIFEST_SCHEMA = "mhl.catalog-manifest/2";
    private static final String IMPORTER_VERSION = "jdbc-catalog-import-v7.1";
    private static final String NORMALIZATION_VERSION = "catalog-normalization-v7.1";
    private static final String FINGERPRINT_MODEL = "sha256:file";
    private static final int FINGERPRINT_VERSION = 1;
    private static final String PROCESSING_FLAGS = "streaming,bounded-batches,stable-book-id";
    private static final String FEATURES_ENABLED = "provenance,relations,artifact-occurrences,selective-search-index";

    String sourceKey(ImportContext context, Path source) {
        if (context.getCatalogSourceKey() != null && !context.getCatalogSourceKey().isBlank()) {
            return context.getCatalogSourceKey().trim();
        }
        return "local-catalog:" + source.toString().replace('\\', '/');
    }

    SourceStat stat(Path source) {
        try {
            return new SourceStat(Files.size(source), Files.getLastModifiedTime(source).toMillis());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot stat " + source, e);
        }
    }

    String fingerprint(Path source, AtomicBoolean cancel) {
        try {
            return Sha256Support.file(source, () -> cancel != null && cancel.get()).orElse("");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint catalog " + source, e);
        }
    }

    void ensureSourceRow(JdbcTemplate jdbc, String sourceId, String sourceKey, String sourceLocation, String fingerprint) {
        jdbc.update("""
                INSERT INTO catalog_sources(source_id,source_key,source_location,source_revision,source_fingerprint,profile_type)
                VALUES (?,?,?,1,?,'catalog')
                ON CONFLICT(source_key) DO UPDATE SET
                    source_location=excluded.source_location,
                    source_fingerprint=excluded.source_fingerprint,
                    last_synced_at=CURRENT_TIMESTAMP
                """, sourceId, sourceKey, sanitizeLocation(sourceLocation), fingerprint);
    }

    Manifest find(JdbcTemplate jdbc, String sourceKey) {
        List<Manifest> rows = jdbc.query("""
                SELECT COALESCE(source_path,'') AS source_path,size_bytes,mtime_millis,fingerprint,parser_version,
                       COALESCE(record_count,0) AS record_count,COALESCE(dataset_schema,'') AS dataset_schema,
                       COALESCE(manifest_schema,'') AS manifest_schema,COALESCE(importer_version,'') AS importer_version,
                       COALESCE(source_format,'') AS source_format,COALESCE(normalization_version,'') AS normalization_version,
                       COALESCE(fingerprint_model,'') AS fingerprint_model,COALESCE(fingerprint_version,0) AS fingerprint_version,
                       COALESCE(processing_flags,'') AS processing_flags,COALESCE(features_enabled,'') AS features_enabled,
                       COALESCE(dataset_normalization_model,'') AS dataset_normalization_model
                  FROM catalog_manifests WHERE source_key=?
                """, (rs, rowNum) -> new Manifest(
                safe(rs.getString("source_path")), rs.getLong("size_bytes"), rs.getLong("mtime_millis"),
                safe(rs.getString("fingerprint")), safe(rs.getString("parser_version")),
                rs.getLong("record_count"), safe(rs.getString("dataset_schema")),
                safe(rs.getString("manifest_schema")), safe(rs.getString("importer_version")),
                safe(rs.getString("source_format")), safe(rs.getString("normalization_version")),
                safe(rs.getString("fingerprint_model")), rs.getInt("fingerprint_version"),
                safe(rs.getString("processing_flags")), safe(rs.getString("features_enabled")),
                safe(rs.getString("dataset_normalization_model"))), sourceKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    boolean isCompatible(Manifest manifest, Path source, String sourceFormat) {
        if (manifest == null) return false;
        String normalizedPath = source.toAbsolutePath().normalize().toString();
        return normalizedPath.equals(manifest.sourcePath())
                && MANIFEST_SCHEMA.equals(manifest.manifestSchema())
                && PARSER_VERSION.equals(manifest.parserVersion())
                && IMPORTER_VERSION.equals(manifest.importerVersion())
                && safe(sourceFormat).equals(manifest.sourceFormat())
                && NORMALIZATION_VERSION.equals(manifest.normalizationVersion())
                && FINGERPRINT_MODEL.equals(manifest.fingerprintModel())
                && FINGERPRINT_VERSION == manifest.fingerprintVersion()
                && PROCESSING_FLAGS.equals(manifest.processingFlags())
                && FEATURES_ENABLED.equals(manifest.featuresEnabled());
    }

    void touch(JdbcTemplate jdbc, String sourceKey, Path source, SourceStat stat, String fingerprint,
               long recordCount, String datasetSchema, String sourceFormat, String datasetNormalizationModel) {
        jdbc.update("""
                INSERT INTO catalog_manifests(
                    source_key,source_path,size_bytes,mtime_millis,fingerprint,parser_version,record_count,dataset_schema,
                    manifest_schema,importer_version,source_format,normalization_version,fingerprint_model,fingerprint_version,
                    processing_flags,features_enabled,dataset_normalization_model,last_parsed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(source_key) DO UPDATE SET
                    source_path=excluded.source_path,size_bytes=excluded.size_bytes,mtime_millis=excluded.mtime_millis,
                    fingerprint=excluded.fingerprint,parser_version=excluded.parser_version,record_count=excluded.record_count,
                    dataset_schema=excluded.dataset_schema,manifest_schema=excluded.manifest_schema,
                    importer_version=excluded.importer_version,source_format=excluded.source_format,
                    normalization_version=excluded.normalization_version,fingerprint_model=excluded.fingerprint_model,
                    fingerprint_version=excluded.fingerprint_version,processing_flags=excluded.processing_flags,
                    features_enabled=excluded.features_enabled,dataset_normalization_model=excluded.dataset_normalization_model,
                    last_parsed_at=CURRENT_TIMESTAMP
                """, sourceKey, source.toAbsolutePath().normalize().toString(), stat.sizeBytes(), stat.mtimeMillis(),
                fingerprint, PARSER_VERSION, recordCount, datasetSchema, MANIFEST_SCHEMA, IMPORTER_VERSION,
                safe(sourceFormat), NORMALIZATION_VERSION, FINGERPRINT_MODEL, FINGERPRINT_VERSION, PROCESSING_FLAGS,
                FEATURES_ENABLED, blankToNull(datasetNormalizationModel));
    }

    private static String sanitizeLocation(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            if (uri.getScheme() != null && uri.getHost() != null) {
                return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
            }
        } catch (Exception ignored) {
            // Non-URI values may still be valid local source descriptions; redact known secret-looking parameters below.
        }
        return value.replaceAll("(?i)(password|token|key|secret)=[^&\\s]+", "$1=<redacted>");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record SourceStat(long sizeBytes, long mtimeMillis) { }

    record Manifest(String sourcePath, long sizeBytes, long mtimeMillis, String fingerprint,
                    String parserVersion, long recordCount, String datasetSchema, String manifestSchema,
                    String importerVersion, String sourceFormat, String normalizationVersion,
                    String fingerprintModel, int fingerprintVersion, String processingFlags,
                    String featuresEnabled, String datasetNormalizationModel) { }
}
