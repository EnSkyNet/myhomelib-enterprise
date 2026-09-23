package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.dto.ReadingSessionRecord;
import com.myhomelibcorp.application.dto.ReadingStatisticsDto;
import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.statistics.ReadingStatisticsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/** Tracks bounded in-process Reader sessions and persists completed sessions atomically. */
@Service
@RequiredArgsConstructor
public class ReadingSessionService {

    private final ReadingStatisticsPort statisticsPort;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;
    private final ConcurrentMap<SessionKey, ActiveSession> activeSessions = new ConcurrentHashMap<>();

    public void start(String bookId, int startPercent) {
        String collectionId = activeCollectionId();
        if (collectionId != null) start(collectionId, bookId, startPercent);
    }

    public void start(String collectionId, String bookId, int startPercent) {
        if (collectionId == null || collectionId.isBlank() || bookId == null || bookId.isBlank()) return;
        if (!isActiveCollection(collectionId)) return;
        // A duplicate UI callback must not reset the session timer and silently lose elapsed time.
        activeSessions.putIfAbsent(new SessionKey(collectionId, bookId), new ActiveSession(
                System.nanoTime(), LocalDateTime.now(), clampPercent(startPercent)));
    }

    public void finish(String bookId, int currentPercent) {
        String collectionId = activeCollectionId();
        if (collectionId != null) finish(collectionId, bookId, currentPercent);
    }

    public void finish(String collectionId, String bookId, int currentPercent) {
        if (collectionId == null || collectionId.isBlank() || bookId == null || bookId.isBlank()) return;
        ActiveSession session = activeSessions.remove(new SessionKey(collectionId, bookId));
        if (session == null) return;

        LibraryOperationCoordinator.Lease lease;
        try {
            lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.READER_STATE);
        } catch (LibraryOperationConflictException lifecycleBusy) {
            // If SWITCH/RESTORE/DELETE already owns the lifecycle, the old Reader must not
            // publish its session into whichever collection becomes active next.
            return;
        }
        try {
            if (!isActiveCollection(collectionId)) {
                lease.markCompleted("stale reader session dropped");
                return;
            }
            LocalDateTime endedAt = LocalDateTime.now();
            long durationSeconds = Math.max(0L,
                    TimeUnit.NANOSECONDS.toSeconds(Math.max(0L, System.nanoTime() - session.startedNanos())));
            statisticsPort.recordSession(new ReadingSessionRecord(
                    bookId,
                    session.startedAt(),
                    endedAt,
                    durationSeconds,
                    session.startPercent(),
                    clampPercent(currentPercent)));
            lease.markCompleted("");
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    public Optional<ReadingStatisticsDto> find(String bookId) {
        if (bookId == null || bookId.isBlank()) return Optional.empty();
        LibraryOperationCoordinator.Lease lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.READER_STATE);
        try {
            Optional<ReadingStatisticsDto> result = statisticsPort.findByBookId(bookId);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    public void discard(String bookId) {
        if (bookId == null) return;
        String collectionId = activeCollectionId();
        if (collectionId != null) discard(collectionId, bookId);
    }

    public void discard(String collectionId, String bookId) {
        if (collectionId != null && bookId != null) activeSessions.remove(new SessionKey(collectionId, bookId));
    }

    int activeSessionCount() {
        return activeSessions.size();
    }

    private String activeCollectionId() {
        var active = collectionLifecyclePort.getCurrentCollection();
        return active == null ? null : active.getId();
    }

    private boolean isActiveCollection(String collectionId) {
        var active = collectionLifecyclePort.getCurrentCollection();
        return active != null && active.getId() != null && active.getId().equals(collectionId);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private record SessionKey(String collectionId, String bookId) { }
    private record ActiveSession(long startedNanos, LocalDateTime startedAt, int startPercent) { }
}
