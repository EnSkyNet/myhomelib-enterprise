package com.myhomelibcorp.application.usecase.author;

import com.myhomelibcorp.application.dto.AuthorBookStatistics;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.statistics.AuthorBookStatisticsPort;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LoadAuthorBookStatisticsUseCase {
    private final AuthorBookStatisticsPort statisticsPort;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public LoadAuthorBookStatisticsUseCase(AuthorBookStatisticsPort statisticsPort) {
        this(statisticsPort, null);
    }

    @Autowired
    public LoadAuthorBookStatisticsUseCase(AuthorBookStatisticsPort statisticsPort,
                                           LibraryOperationCoordinator operationCoordinator) {
        this.statisticsPort = java.util.Objects.requireNonNull(statisticsPort, "statisticsPort");
        this.operationCoordinator = operationCoordinator;
    }

    public AuthorBookStatistics execute(AuthorId authorId) {
        if (authorId == null) return AuthorBookStatistics.empty();
        if (operationCoordinator == null) return statisticsPort.load(authorId);

        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            AuthorBookStatistics result = statisticsPort.load(authorId);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }
}
