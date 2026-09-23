package com.myhomelibcorp.application.activity;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.activity.BookActivityQueryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BookActivityService {
    private final BookActivityQueryPort queryPort;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public BookActivityService(BookActivityQueryPort queryPort) {
        this(queryPort, null);
    }

    @Autowired
    public BookActivityService(BookActivityQueryPort queryPort,
                               LibraryOperationCoordinator operationCoordinator) {
        this.queryPort = java.util.Objects.requireNonNull(queryPort, "queryPort");
        this.operationCoordinator = operationCoordinator;
    }

    public Map<String, BookActivitySummary> summarize(Collection<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Map.of();
        List<String> ids = bookIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, BookActivitySummary> loaded;
        if (operationCoordinator == null) {
            loaded = queryPort.summarize(ids);
        } else {
            var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
            try {
                loaded = queryPort.summarize(ids);
                lease.markCompleted("");
            } catch (RuntimeException | Error failure) {
                lease.markFailed(failure);
                throw failure;
            } finally {
                lease.close();
            }
        }
        if (loaded == null) loaded = Map.of();
        LinkedHashMap<String, BookActivitySummary> result = new LinkedHashMap<>();
        for (String id : ids) result.put(id, loaded.getOrDefault(id, BookActivitySummary.empty(id)));
        return Map.copyOf(result);
    }
}
