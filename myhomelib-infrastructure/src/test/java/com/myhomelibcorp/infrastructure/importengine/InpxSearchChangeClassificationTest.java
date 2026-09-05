package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InpxSearchChangeClassificationTest {

    @Test
    void repeatedDeletedRowsDoNotConsumeChangeTrackingCapacity() {
        var changes = new ImportChangeAccumulator(1);
        var oldDeleted = new InpxImportPipeline.ExistingSearchState(
                true, true, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        for (int i = 0; i < 100; i++) {
            InpxImportPipeline.classifySearchChange(
                    "deleted-" + i, true, "search-a", "catalog-a", oldDeleted, changes);
        }

        var result = changes.snapshot();
        assertThat(result.complete()).isTrue();
        assertThat(result.insertedCount()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.deletedCount()).isZero();
    }

    @Test
    void activeBookBecomingDeletedIsRecordedOnce() {
        var changes = new ImportChangeAccumulator(10);
        var oldActive = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        InpxImportPipeline.classifySearchChange(
                "book-1", true, "search-deleted", "catalog-deleted", oldActive, changes);

        var result = changes.snapshot();
        assertThat(result.deleted()).containsExactly("book-1");
        assertThat(result.deletedCount()).isEqualTo(1);
    }

    @Test
    void reactivatedDeletedBookIsInsertedIntoSearchIndex() {
        var changes = new ImportChangeAccumulator(10);
        var oldDeleted = new InpxImportPipeline.ExistingSearchState(
                true, true, false, "catalog-old", "mhl.lucene.searchable-metadata", 1, "search-old");

        InpxImportPipeline.classifySearchChange(
                "book-1", false, "search-new", "catalog-new", oldDeleted, changes);

        var result = changes.snapshot();
        assertThat(result.inserted()).containsExactly("book-1");
        assertThat(result.deletedCount()).isZero();
    }

    @Test
    void unchangedActiveBookStaysUnchangedAndSearchMetadataChangeIsUpdated() {
        var changes = new ImportChangeAccumulator(10);
        var oldActive = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        InpxImportPipeline.classifySearchChange(
                "same", false, "search-a", "catalog-a", oldActive, changes);
        InpxImportPipeline.classifySearchChange(
                "changed", false, "search-b", "catalog-b", oldActive, changes);

        var result = changes.snapshot();
        assertThat(result.updated()).containsExactly("changed");
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.deletedCount()).isZero();
    }
    @Test
    void unchangedCatalogSkipsBookWriteButLocalPromotionStillPersists() {
        var oldRemote = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");
        var oldLocal = new InpxImportPipeline.ExistingSearchState(
                true, false, true, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        assertThat(InpxImportPipeline.requiresBookPersistence("catalog-a", false, oldRemote)).isFalse();
        assertThat(InpxImportPipeline.requiresBookPersistence("catalog-a", true, oldRemote)).isTrue();
        assertThat(InpxImportPipeline.requiresBookPersistence("catalog-a", false, oldLocal)).isFalse();
        assertThat(InpxImportPipeline.requiresBookPersistence("catalog-b", false, oldLocal)).isTrue();
        assertThat(InpxImportPipeline.requiresBookPersistence("catalog-new", false, null)).isTrue();
    }

    @Test
    void legacyUnchangedCatalogDoesNotBackfillSearchStateButRealSearchChangeDoes() {
        var legacy = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", null, null, null);
        var current = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        assertThat(InpxImportPipeline.requiresSearchStatePersistence(
                false, "search-a", "catalog-a", legacy)).isFalse();
        assertThat(InpxImportPipeline.requiresSearchStatePersistence(
                false, "search-b", "catalog-b", legacy)).isTrue();
        assertThat(InpxImportPipeline.requiresSearchStatePersistence(
                false, "search-a", "catalog-a", current)).isFalse();
        assertThat(InpxImportPipeline.requiresSearchStatePersistence(
                false, "search-b", "catalog-b", current)).isTrue();
        assertThat(InpxImportPipeline.requiresSearchStatePersistence(
                true, "search-deleted", "catalog-deleted", current)).isFalse();
    }



    @Test
    void previewSearchFingerprintIsComputedOnlyWhenCatalogStateCannotProveSearchIsUnchanged() {
        var current = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");
        var legacy = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", null, null, null);
        var staleModel = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", "old-search-model", 1, "search-old");
        var staleVersion = new InpxImportPipeline.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 0, "search-old");
        var deleted = new InpxImportPipeline.ExistingSearchState(
                true, true, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        assertThat(InpxImportPipeline.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", current)).isFalse();
        assertThat(InpxImportPipeline.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", legacy)).isFalse();
        assertThat(InpxImportPipeline.shouldComputePreviewSearchFingerprint(
                true, "catalog-b", current)).isFalse();

        assertThat(InpxImportPipeline.shouldComputePreviewSearchFingerprint(
                false, "catalog-b", current)).isTrue();
        assertThat(InpxImportPipeline.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", staleModel)).isTrue();
        assertThat(InpxImportPipeline.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", staleVersion)).isTrue();
        assertThat(InpxImportPipeline.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", deleted)).isTrue();
        assertThat(InpxImportPipeline.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", null)).isTrue();
    }

}
