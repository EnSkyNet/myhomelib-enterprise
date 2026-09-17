package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.operation.LibraryOperationCompletion;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationOutcome;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Adds coordinator-only lifecycle work (most importantly automatic Lucene rebuilds) to the
 * Operations journal. Operations that already publish richer progress are left untouched.
 * Terminal coordinator outcomes are recorded as success/cancel/failure so the history does not
 * falsely report a failed background rebuild as successful.
 */
@Component
@RequiredArgsConstructor
public final class CoordinatedOperationCenterBridge {
    private final LibraryOperationCoordinator coordinator;
    private final OperationCenterService operationCenter;
    private final ApplicationState applicationState;

    private final Object lock = new Object();
    private final Map<LibraryOperationType, String> syntheticOperations = new EnumMap<>(LibraryOperationType.class);
    private AutoCloseable activeRegistration;
    private AutoCloseable completionRegistration;

    @PostConstruct
    void start() {
        // Register terminal outcomes first so even a very short operation cannot leave an active row behind.
        completionRegistration = coordinator.addCompletionListener(this::onOperationCompleted);
        activeRegistration = coordinator.addListener(this::onOperationChanged);
    }

    @PreDestroy
    void stop() {
        closeQuietly(activeRegistration);
        closeQuietly(completionRegistration);
        activeRegistration = null;
        completionRegistration = null;
    }

    private void onOperationChanged(LibraryOperationType operation) {
        if (operation == null || !needsCoordinatorJournalEntry(operation)) return;
        synchronized (lock) {
            if (syntheticOperations.containsKey(operation)) return;

            OperationKind kind = kind(operation);
            // Manual Lucene rebuild already has a richer progress row. SWITCH and SYNC do not, and
            // both intentionally share the generic MAINTENANCE category with other tools, so using
            // hasActiveKind(MAINTENANCE) for them could incorrectly suppress their own history row.
            if (operation == LibraryOperationType.INDEX && operationCenter.hasActiveKind(kind)) return;

            String collectionId = applicationState.getCurrentLibraryCollectionId();
            if (collectionId == null) collectionId = "";
            String collectionName = applicationState.getCurrentLibraryCollectionName();
            String title = title(operation);
            if (collectionName != null && !collectionName.isBlank()) {
                title += " · " + collectionName.trim();
            }
            String operationId = operationCenter.start(title, collectionId, kind, stage(operation), false);
            syntheticOperations.put(operation, operationId);
        }
    }

    private void onOperationCompleted(LibraryOperationCompletion completion) {
        if (completion == null) return;
        synchronized (lock) {
            String operationId = syntheticOperations.remove(completion.operation());
            if (operationId == null) return;
            String detail = completion.detail() == null ? "" : completion.detail().trim();
            if (completion.outcome() == LibraryOperationOutcome.FAILED) {
                operationCenter.fail(operationId, detail);
            } else if (completion.outcome() == LibraryOperationOutcome.CANCELLED) {
                operationCenter.cancel(operationId, detail.isBlank() ? "Операцію скасовано" : detail);
            } else {
                operationCenter.complete(operationId, detail.isBlank() ? "Операцію завершено" : detail);
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) { }
    }

    private static boolean needsCoordinatorJournalEntry(LibraryOperationType operation) {
        return operation == LibraryOperationType.INDEX
                || operation == LibraryOperationType.SWITCH
                || operation == LibraryOperationType.SYNC;
    }

    private static OperationKind kind(LibraryOperationType operation) {
        return operation == LibraryOperationType.INDEX ? OperationKind.INDEX_REBUILD : OperationKind.MAINTENANCE;
    }

    private static OperationStage stage(LibraryOperationType operation) {
        return switch (operation) {
            case INDEX -> OperationStage.UPDATING_SEARCH_INDEX;
            case SYNC -> OperationStage.SYNCHRONIZING_FILES;
            case SWITCH -> OperationStage.FINALIZING;
            default -> OperationStage.FINALIZING;
        };
    }

    private static String title(LibraryOperationType operation) {
        return switch (operation) {
            case INDEX -> "Оновлення пошукового індексу";
            case SWITCH -> "Перемикання колекції";
            case SYNC -> "Синхронізація колекції";
            default -> "Фонова операція";
        };
    }
}
