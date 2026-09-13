package com.myhomelibcorp.application.port.out.opds;

import com.myhomelibcorp.application.opds.OpdsBookDto;
import com.myhomelibcorp.application.opds.OpdsBookQuery;
import com.myhomelibcorp.application.opds.OpdsFacetDto;
import com.myhomelibcorp.application.opds.OpdsPage;

import java.util.Optional;

/** Bounded read-only catalogue projection used by OPDS and other sidecars. */
public interface OpdsCatalogQueryPort {
    OpdsPage<OpdsFacetDto> authors(int offset, int limit);
    OpdsPage<OpdsFacetDto> series(int offset, int limit);
    OpdsPage<OpdsFacetDto> genres(int offset, int limit);
    OpdsPage<OpdsBookDto> books(OpdsBookQuery query);
    Optional<OpdsBookDto> book(String bookId);

    /** Active MyHomeLib collection exposed by the OPDS sidecar. */
    Optional<OpdsFacetDto> currentCollection();

    /** User groups in the active collection. */
    OpdsPage<OpdsFacetDto> groups(int offset, int limit);

    /** Books assigned to one user group. */
    OpdsPage<OpdsBookDto> groupBooks(String groupId, int offset, int limit);

    /** Books in the built-in Favorites group. */
    OpdsPage<OpdsBookDto> favorites(int offset, int limit);

    /** In-progress books ordered by the most recently updated reading position. */
    OpdsPage<OpdsBookDto> continueReading(int offset, int limit);
}
