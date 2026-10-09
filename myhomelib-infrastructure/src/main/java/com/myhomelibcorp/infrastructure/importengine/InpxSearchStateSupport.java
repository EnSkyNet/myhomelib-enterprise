package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;

import java.util.Objects;

/**
 * Search-index state policy used by INPX preview and batch import paths.
 *
 * <p>This boundary owns only the decision rules that compare incoming catalog/search fingerprints
 * with persisted state. JDBC loading/persistence and import orchestration remain in
 * {@link InpxImportPipeline}.</p>
 */
final class InpxSearchStateSupport {
    static final String SEARCH_FP_MODEL = "mhl.lucene.searchable-metadata";
    static final int SEARCH_FP_VERSION = 1;

    private InpxSearchStateSupport() { }

    record ExistingSearchState(
            boolean exists,
            boolean deleted,
            boolean local,
            String catalogFingerprint,
            String searchModel,
            Integer searchVersion,
            String searchFingerprint) { }

    static boolean requiresBookPersistence(
            String incomingCatalogFingerprint,
            boolean incomingLocal,
            ExistingSearchState old) {
        if (old == null) return true;
        if (!Objects.equals(old.catalogFingerprint(), incomingCatalogFingerprint)) return true;
        // Preserve existing local bytes when the catalog says remote; only a newly discovered local file
        // requires a write when catalog metadata itself is unchanged.
        return incomingLocal && !old.local();
    }

    static boolean shouldComputePreviewSearchFingerprint(
            boolean incomingDeleted,
            String incomingCatalogFingerprint,
            ExistingSearchState old) {
        if (incomingDeleted) return false;
        if (old == null || old.deleted()) return true;
        if (!Objects.equals(old.catalogFingerprint(), incomingCatalogFingerprint)) return true;
        if (old.searchFingerprint() == null) return false;
        return !SEARCH_FP_MODEL.equals(old.searchModel())
                || old.searchVersion() == null
                || old.searchVersion() != SEARCH_FP_VERSION;
    }

    static boolean requiresSearchStatePersistence(
            boolean incomingDeleted,
            String incomingSearchFingerprint,
            String incomingCatalogFingerprint,
            ExistingSearchState old) {
        if (incomingDeleted) return false;
        if (old == null || old.deleted()) return true;
        boolean sameSearch = SEARCH_FP_MODEL.equals(old.searchModel())
                && old.searchVersion() != null && old.searchVersion() == SEARCH_FP_VERSION
                && Objects.equals(old.searchFingerprint(), incomingSearchFingerprint);
        if (sameSearch) return false;
        // Initial baselines intentionally omit book_search_state. If the complete catalog fingerprint
        // is unchanged, the legacy fallback remains a valid proof that searchable metadata is unchanged.
        return !(old.searchFingerprint() == null
                && old.catalogFingerprint() != null
                && Objects.equals(old.catalogFingerprint(), incomingCatalogFingerprint));
    }

    static void classifySearchChange(
            String id,
            boolean incomingDeleted,
            String incomingSearchFingerprint,
            String incomingCatalogFingerprint,
            ExistingSearchState old,
            ImportChangeAccumulator changes) {
        if (incomingDeleted) {
            // A tombstone is a search change only when an actually indexed/active book becomes deleted.
            // Replaying an already-deleted row must not consume bounded change-tracking capacity.
            if (old != null && !old.deleted()) changes.recordDeleted(id);
            else changes.markUnchanged(id);
            return;
        }
        if (old == null || old.deleted()) {
            // New and re-activated books both need to be present in Lucene after the update.
            changes.recordInserted(id);
            return;
        }
        boolean sameSearch = SEARCH_FP_MODEL.equals(old.searchModel())
                && old.searchVersion() != null && old.searchVersion() == SEARCH_FP_VERSION
                && Objects.equals(old.searchFingerprint(), incomingSearchFingerprint);
        // Upgrade fallback: an unchanged v7 catalog fingerprint proves searchable metadata did not change.
        boolean sameLegacyCatalog = old.searchFingerprint() == null
                && old.catalogFingerprint() != null
                && Objects.equals(old.catalogFingerprint(), incomingCatalogFingerprint);
        if (!sameSearch && !sameLegacyCatalog && !changes.containsInserted(id)) changes.recordUpdated(id);
        else changes.markUnchanged(id);
    }
}
