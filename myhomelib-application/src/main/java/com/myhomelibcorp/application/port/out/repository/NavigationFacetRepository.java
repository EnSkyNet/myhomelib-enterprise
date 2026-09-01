package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.filter.BookFilterSpec;

import java.util.List;
import java.util.Optional;

/**
 * Aggregated catalogue facets used by navigation. Implementations must aggregate
 * in the database and apply the same {@link BookFilterSpec} used by table/search.
 */
public interface NavigationFacetRepository {

    List<Facet> findAuthors(char initial, BookFilterSpec filter);
    List<Facet> findDownloadedAuthors(BookFilterSpec filter);
    Optional<Character> findFirstAuthorInitial(BookFilterSpec filter);
    List<Facet> findSeries(BookFilterSpec filter);
    List<Facet> findGenres(BookFilterSpec filter);
    List<Facet> findYears(BookFilterSpec filter);
    List<Facet> findLanguages(BookFilterSpec filter);
    List<ArchiveFacet> findArchives(BookFilterSpec filter);
    List<Facet> findKeywords(BookFilterSpec filter);
    List<Facet> findGroups(BookFilterSpec filter);
    List<Facet> findReviewSubsets(BookFilterSpec filter);

    default List<Facet> findYears() { return findYears(BookFilterSpec.empty()); }
    default List<Facet> findLanguages() { return findLanguages(BookFilterSpec.empty()); }
    default List<ArchiveFacet> findArchives() { return findArchives(BookFilterSpec.empty()); }
    default List<Facet> findKeywords() { return findKeywords(BookFilterSpec.empty()); }
    default List<Facet> findGroups() { return findGroups(BookFilterSpec.empty()); }
    default List<Facet> findReviewSubsets() { return findReviewSubsets(BookFilterSpec.empty()); }

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
