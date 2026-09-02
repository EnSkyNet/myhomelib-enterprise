package com.myhomelibcorp.application.port.out.catalog;

import com.myhomelibcorp.application.catalog.CatalogBookSnapshot;
import com.myhomelibcorp.application.catalog.CatalogSyncSession;
import com.myhomelibcorp.application.catalog.CatalogUpdateCursor;
import com.myhomelibcorp.application.catalog.CatalogUpdateItem;
import com.myhomelibcorp.application.catalog.CatalogUpdateType;
import com.myhomelibcorp.application.catalog.FollowedAuthorSummary;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

/** Persistence boundary for Stage 6 catalog revision/update tracking. */
public interface CatalogUpdateTrackingPort {
    CatalogSyncSession beginSync(String sourceKey, String sourceLocation, String sourceFingerprint);

    /** Mark rows previously seen from this source as absent before the new revision is replayed. */
    void markTrackedBooksMissing(CatalogSyncSession session);

    /** Called after books and their author links have been UPSERTed for the current batch. */
    void recordImportedBooks(CatalogSyncSession session, List<CatalogBookSnapshot> books);

    /** Capture the current catalog revision/fingerprint after a successful download. */
    void markDownloadedBaseline(BookId bookId);

    /** Clear download baseline when the local bytes are explicitly removed. */
    void clearDownloadedBaseline(BookId bookId);

    void setAuthorFollowed(AuthorId authorId, boolean followed);

    boolean isAuthorFollowed(AuthorId authorId);

    /** Followed authors with live catalog counters; never materializes all books in the UI. */
    List<FollowedAuthorSummary> findFollowedAuthors();

    void acknowledgeUpdate(BookId bookId, CatalogUpdateType type);

    /** Acknowledge pending events deterministically grouped under one author in the Updates workspace. */
    void acknowledgeAuthorUpdates(AuthorId authorId);

    void acknowledgeAllUpdates();

    /**
     * Pending rows already enriched with title and a deterministic author for Stage 7 grouping.
     * NEW_BY_FOLLOWED_AUTHOR prefers a followed co-author; other rows use the same deterministic
     * ordering so one event is rendered exactly once and counters stay consistent.
     */
    List<CatalogUpdateItem> findPendingUpdateItems(int limit, CatalogUpdateCursor after);

    long countPendingUpdates();
}
