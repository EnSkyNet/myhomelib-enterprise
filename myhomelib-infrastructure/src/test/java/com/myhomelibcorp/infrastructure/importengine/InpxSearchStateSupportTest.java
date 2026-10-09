package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InpxSearchStateSupportTest {

    @Test
    void repeatedDeletedRowsDoNotConsumeChangeTrackingCapacity() {
        var changes = new ImportChangeAccumulator(1);
        var oldDeleted = new InpxSearchStateSupport.ExistingSearchState(
                true, true, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        for (int i = 0; i < 100; i++) {
            InpxSearchStateSupport.classifySearchChange(
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
        var oldActive = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        InpxSearchStateSupport.classifySearchChange(
                "book-1", true, "search-deleted", "catalog-deleted", oldActive, changes);

        var result = changes.snapshot();
        assertThat(result.deleted()).containsExactly("book-1");
        assertThat(result.deletedCount()).isEqualTo(1);
    }

    @Test
    void reactivatedDeletedBookIsInsertedIntoSearchIndex() {
        var changes = new ImportChangeAccumulator(10);
        var oldDeleted = new InpxSearchStateSupport.ExistingSearchState(
                true, true, false, "catalog-old", "mhl.lucene.searchable-metadata", 1, "search-old");

        InpxSearchStateSupport.classifySearchChange(
                "book-1", false, "search-new", "catalog-new", oldDeleted, changes);

        var result = changes.snapshot();
        assertThat(result.inserted()).containsExactly("book-1");
        assertThat(result.deletedCount()).isZero();
    }

    @Test
    void unchangedActiveBookStaysUnchangedAndSearchMetadataChangeIsUpdated() {
        var changes = new ImportChangeAccumulator(10);
        var oldActive = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        InpxSearchStateSupport.classifySearchChange(
                "same", false, "search-a", "catalog-a", oldActive, changes);
        InpxSearchStateSupport.classifySearchChange(
                "changed", false, "search-b", "catalog-b", oldActive, changes);

        var result = changes.snapshot();
        assertThat(result.updated()).containsExactly("changed");
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.deletedCount()).isZero();
    }
    @Test
    void unchangedCatalogSkipsBookWriteButLocalPromotionStillPersists() {
        var oldRemote = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");
        var oldLocal = new InpxSearchStateSupport.ExistingSearchState(
                true, false, true, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        assertThat(InpxSearchStateSupport.requiresBookPersistence("catalog-a", false, oldRemote)).isFalse();
        assertThat(InpxSearchStateSupport.requiresBookPersistence("catalog-a", true, oldRemote)).isTrue();
        assertThat(InpxSearchStateSupport.requiresBookPersistence("catalog-a", false, oldLocal)).isFalse();
        assertThat(InpxSearchStateSupport.requiresBookPersistence("catalog-b", false, oldLocal)).isTrue();
        assertThat(InpxSearchStateSupport.requiresBookPersistence("catalog-new", false, null)).isTrue();
    }

    @Test
    void legacyUnchangedCatalogDoesNotBackfillSearchStateButRealSearchChangeDoes() {
        var legacy = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", null, null, null);
        var current = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        assertThat(InpxSearchStateSupport.requiresSearchStatePersistence(
                false, "search-a", "catalog-a", legacy)).isFalse();
        assertThat(InpxSearchStateSupport.requiresSearchStatePersistence(
                false, "search-b", "catalog-b", legacy)).isTrue();
        assertThat(InpxSearchStateSupport.requiresSearchStatePersistence(
                false, "search-a", "catalog-a", current)).isFalse();
        assertThat(InpxSearchStateSupport.requiresSearchStatePersistence(
                false, "search-b", "catalog-b", current)).isTrue();
        assertThat(InpxSearchStateSupport.requiresSearchStatePersistence(
                true, "search-deleted", "catalog-deleted", current)).isFalse();
    }



    @Test
    void previewSearchFingerprintIsComputedOnlyWhenCatalogStateCannotProveSearchIsUnchanged() {
        var current = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");
        var legacy = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", null, null, null);
        var staleModel = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", "old-search-model", 1, "search-old");
        var staleVersion = new InpxSearchStateSupport.ExistingSearchState(
                true, false, false, "catalog-a", "mhl.lucene.searchable-metadata", 0, "search-old");
        var deleted = new InpxSearchStateSupport.ExistingSearchState(
                true, true, false, "catalog-a", "mhl.lucene.searchable-metadata", 1, "search-a");

        assertThat(InpxSearchStateSupport.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", current)).isFalse();
        assertThat(InpxSearchStateSupport.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", legacy)).isFalse();
        assertThat(InpxSearchStateSupport.shouldComputePreviewSearchFingerprint(
                true, "catalog-b", current)).isFalse();

        assertThat(InpxSearchStateSupport.shouldComputePreviewSearchFingerprint(
                false, "catalog-b", current)).isTrue();
        assertThat(InpxSearchStateSupport.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", staleModel)).isTrue();
        assertThat(InpxSearchStateSupport.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", staleVersion)).isTrue();
        assertThat(InpxSearchStateSupport.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", deleted)).isTrue();
        assertThat(InpxSearchStateSupport.shouldComputePreviewSearchFingerprint(
                false, "catalog-a", null)).isTrue();
    }

}
