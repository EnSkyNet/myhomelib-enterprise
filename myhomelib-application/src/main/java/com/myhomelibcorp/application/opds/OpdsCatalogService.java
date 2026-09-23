package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

@Component
public class OpdsCatalogService {
    private final OpdsCatalogQueryPort port;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests and non-Spring embedding. */
    public OpdsCatalogService(OpdsCatalogQueryPort port) {
        this(port, null);
    }

    @Autowired
    public OpdsCatalogService(OpdsCatalogQueryPort port, LibraryOperationCoordinator operationCoordinator) {
        this.port = java.util.Objects.requireNonNull(port, "port");
        this.operationCoordinator = operationCoordinator;
    }

    public OpdsPage<OpdsFacetDto> authors(int offset, int limit) {
        return query(() -> port.authors(offset, clamp(limit)));
    }

    public OpdsPage<OpdsFacetDto> series(int offset, int limit) {
        return query(() -> port.series(offset, clamp(limit)));
    }

    public OpdsPage<OpdsFacetDto> genres(int offset, int limit) {
        return query(() -> port.genres(offset, clamp(limit)));
    }

    public OpdsPage<OpdsBookDto> books(OpdsBookQuery query) {
        return query(() -> port.books(query));
    }

    public Optional<OpdsBookDto> book(String id) {
        return query(() -> port.book(id));
    }

    public Optional<OpdsFacetDto> currentCollection() {
        return query(port::currentCollection);
    }

    public OpdsPage<OpdsFacetDto> groups(int offset, int limit) {
        return query(() -> port.groups(offset, clamp(limit)));
    }

    public OpdsPage<OpdsBookDto> groupBooks(String groupId, int offset, int limit) {
        return query(() -> port.groupBooks(groupId, Math.max(0, offset), clamp(limit)));
    }

    public OpdsPage<OpdsBookDto> favorites(int offset, int limit) {
        return query(() -> port.favorites(Math.max(0, offset), clamp(limit)));
    }

    public OpdsPage<OpdsBookDto> continueReading(int offset, int limit) {
        return query(() -> port.continueReading(Math.max(0, offset), clamp(limit)));
    }

    private <T> T query(Supplier<T> action) {
        if (operationCoordinator == null) return action.get();
        LibraryOperationCoordinator.Lease lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            return action.get();
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private static int clamp(int limit) { return Math.max(1, Math.min(100, limit)); }
}
