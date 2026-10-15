package com.myhomelibcorp.application.statistics;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Supplier;

@Service
@Slf4j
public class StatisticsService {

    private final StatisticsRepository statisticsRepository;
    private final LibraryOperationCoordinator operationCoordinator;
    /** A status-bar auto-refresh and an explicit user refresh may share one EDIT root. */
    private final Object refreshMonitor = new Object();

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public StatisticsService(StatisticsRepository statisticsRepository) {
        this(statisticsRepository, null);
    }

    @Autowired
    public StatisticsService(StatisticsRepository statisticsRepository,
                             LibraryOperationCoordinator operationCoordinator) {
        this.statisticsRepository = Objects.requireNonNull(statisticsRepository, "statisticsRepository");
        this.operationCoordinator = operationCoordinator;
    }

    public LibraryStatistics getStatistics() {
        log.debug("Отримання collection-aware статистики з активної БД");
        return withOperation(LibraryOperationType.QUERY, statisticsRepository::getStatistics);
    }

    public void invalidate() {
        withOperation(LibraryOperationType.EDIT, () -> {
            statisticsRepository.invalidate();
            return null;
        });
    }

    public void refreshStatistics() {
        log.info("Перерахунок статистики активної колекції");
        withOperation(LibraryOperationType.EDIT, () -> {
            synchronized (refreshMonitor) {
                statisticsRepository.refreshStatistics();
            }
            return null;
        });
    }

    private <T> T withOperation(LibraryOperationType operation, Supplier<T> action) {
        if (operationCoordinator == null || operationCoordinator.isHeldByCurrentThread()) {
            return action.get();
        }

        var lease = operationCoordinator.acquireDetachedShared(operation);
        try {
            T result = action.get();
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
