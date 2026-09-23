package com.myhomelibcorp.application.port.out.content;

import com.myhomelibcorp.application.content.index.ContentIndexEntry;
import com.myhomelibcorp.application.content.index.ContentIndexHealth;
import com.myhomelibcorp.application.content.index.ContentIndexPage;
import com.myhomelibcorp.application.content.index.ContentIndexQuery;


/** Persistence boundary for the independently rebuildable Lucene content index. */
public interface ContentIndexPort {
    void replaceArtifact(String collectionId, ContentIndexEntry entry);
    void deleteArtifact(String collectionId, String artifactId);
    void deleteBook(String collectionId, String bookId);
    void rebuild(String collectionId, Iterable<ContentIndexEntry> entries);
    ContentIndexPage search(ContentIndexQuery query);
    ContentIndexHealth health(String collectionId);

    /** Marks the derived full-text index as not authoritative for the current catalogue state. */
    default void markStale(String collectionId) { }

    /** Clears an earlier stale marker after a complete rebuild or a fully drained incremental update set. */
    default void markFresh(String collectionId) { }
}
