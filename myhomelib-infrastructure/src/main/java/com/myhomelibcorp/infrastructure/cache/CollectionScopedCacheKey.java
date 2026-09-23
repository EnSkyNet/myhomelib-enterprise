package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;

/**
 * Builds cache keys that cannot collide across independently opened collection databases.
 *
 * <p>Collection-local numeric/UUID identifiers and query strings are not globally unique.
 * Cache invalidation on switch is still useful for memory pressure, but correctness must not
 * depend on the exact timing of that invalidation.</p>
 */
final class CollectionScopedCacheKey {
    private static final char SEPARATOR = '\u001f';
    private static final String NO_COLLECTION = "<no-collection>";

    private CollectionScopedCacheKey() { }

    static String of(CollectionManager collectionManager, String localKey) {
        return collectionId(collectionManager) + SEPARATOR + (localKey == null ? "" : localKey);
    }

    private static String collectionId(CollectionManager collectionManager) {
        if (collectionManager == null) return NO_COLLECTION;
        Collection collection = collectionManager.getCurrentCollection();
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) {
            return NO_COLLECTION;
        }
        return collection.getId().trim();
    }
}
