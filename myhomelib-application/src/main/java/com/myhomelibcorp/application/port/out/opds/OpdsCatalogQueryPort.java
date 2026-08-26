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
}
