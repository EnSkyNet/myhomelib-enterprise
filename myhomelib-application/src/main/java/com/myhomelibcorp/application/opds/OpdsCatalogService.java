package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OpdsCatalogService {
    private final OpdsCatalogQueryPort port;

    public OpdsPage<OpdsFacetDto> authors(int offset, int limit) { return port.authors(offset, clamp(limit)); }
    public OpdsPage<OpdsFacetDto> series(int offset, int limit) { return port.series(offset, clamp(limit)); }
    public OpdsPage<OpdsFacetDto> genres(int offset, int limit) { return port.genres(offset, clamp(limit)); }
    public OpdsPage<OpdsBookDto> books(OpdsBookQuery query) { return port.books(query); }
    public Optional<OpdsBookDto> book(String id) { return port.book(id); }

    private static int clamp(int limit) { return Math.max(1, Math.min(100, limit)); }
}
