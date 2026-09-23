package com.myhomelibcorp.application.usecase.reading;

import com.myhomelibcorp.application.dto.ContinueReadingItemDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.ContinueReadingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Application boundary for the cross-device Continue Reading shelf. */
@Service
public final class ContinueReadingService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private final ContinueReadingRepository repository;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public ContinueReadingService(ContinueReadingRepository repository) {
        this(repository, null);
    }

    @Autowired
    public ContinueReadingService(ContinueReadingRepository repository,
                                  LibraryOperationCoordinator operationCoordinator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.operationCoordinator = operationCoordinator;
    }

    public List<ContinueReadingItemDto> recent() {
        return recent(DEFAULT_LIMIT);
    }

    public List<ContinueReadingItemDto> recent(int limit) {
        int safe = Math.max(1, Math.min(MAX_LIMIT, limit));
        if (operationCoordinator == null) return load(safe);
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            List<ContinueReadingItemDto> result = load(safe);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private List<ContinueReadingItemDto> load(int limit) {
        return List.copyOf(repository.findActive(limit));
    }
}
