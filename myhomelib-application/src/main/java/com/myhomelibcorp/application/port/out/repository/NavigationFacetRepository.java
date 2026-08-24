package com.myhomelibcorp.application.port.out.repository;

import java.util.List;

/**
 * Aggregated catalogue facets used by navigation. Implementations should use
 * database-side GROUP BY operations rather than materializing the whole book
 * catalogue in memory.
 */
public interface NavigationFacetRepository {

    List<Facet> findYears();

    List<Facet> findLanguages();

    List<ArchiveFacet> findArchives();

    List<Facet> findKeywords();

    List<Facet> findGroups();

    List<Facet> findReviewSubsets();

    record Facet(String id, String label, long bookCount) {
        public Facet {
            id = id == null ? "" : id.trim();
            label = label == null ? id : label.trim();
            if (bookCount < 0) throw new IllegalArgumentException("bookCount cannot be negative");
        }
    }

    record ArchiveFacet(String collectionRoot, String archivePath, long bookCount) {
        public ArchiveFacet {
            collectionRoot = collectionRoot == null ? "" : collectionRoot;
            archivePath = archivePath == null ? "" : archivePath;
            if (archivePath.isBlank()) throw new IllegalArgumentException("archivePath cannot be blank");
            if (bookCount < 0) throw new IllegalArgumentException("bookCount cannot be negative");
        }
    }
}
