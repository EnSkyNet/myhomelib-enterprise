package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe in-memory history of long-running operations.
 *
 * <p>The service deliberately has no JavaFX dependency: application/background threads may publish telemetry
 * directly, while UI subscribers decide how to marshal snapshots to the FX thread.</p>
 *
 * <p>Composite workflows may reuse one application {@code operationId} for several independently visible jobs
 * (for example: catalog update -> search-index update -> statistics refresh). The operations journal deliberately
 * splits those phases into separate rows, so each row has its own start/end timestamps and duration instead of
 * accumulating the time of all following background work.</p>
 */
@Component
public class OperationCenterService {
    private static final int MAX_HISTORY = 100;
    private static final String PHASE_FINISHED_DETAIL = "Етап завершено; наступний етап показано окремою операцією";

    private final Object lock = new Object();
    private final LinkedHashMap<String, OperationCenterEntry> entries = new LinkedHashMap<>();
    private final Map<String, RootOperationState> roots = new HashMap<>();
    private final CopyOnWriteArrayList<Consumer<List<OperationCenterEntry>>> listeners = new CopyOnWriteArrayList<>();

    public String start(String title, String collectionId, OperationStage stage, boolean cancellable) {
        return start(title, collectionId, inferKind(null, title, stage), stage, cancellable);
    }

    public String start(String title, String collectionId, OperationKind kind, OperationStage stage, boolean cancellable) {
        String id = "ui-operation-" + UUID.randomUUID();
        accept(title, collectionId, kind, OperationProgress.stage(id, stage, cancellable));
        return id;
    }

    public void accept(String title, String collectionId, OperationProgress progress) {
        accept(title, collectionId, null, progress);
    }

    public void accept(String title, String collectionId, OperationKind kind, OperationProgress progress) {
        if (progress == null) return;
        Instant now = Instant.now();
        synchronized (lock) {
            String rootId = progress.operationId();
            RootOperationState root = roots.get(rootId);

            if (root == null) {
                String rootTitle = normalizeTitle(title, "Операція");
                String rootCollection = normalizeCollection(collectionId, "");
                OperationKind rootKind = kind != null ? kind : inferKind(rootId, rootTitle, progress.stage());
                OperationPhase phase = phaseFor(rootKind, progress.stage(), null);
                String entryId = rootId;
                root = new RootOperationState(rootId, entryId, rootTitle, rootCollection, rootKind, phase, 1);
                roots.put(rootId, root);
            }

            OperationPhase desiredPhase = terminal(progress.stage())
                    ? root.phase()
                    : phaseFor(root.rootKind(), progress.stage(), root.phase());

            if (!terminal(progress.stage()) && desiredPhase != root.phase()) {
                closePhaseLocked(root.currentEntryId(), root.rootKind(), now);
                String entryId = nextSegmentId(rootId, root.segmentNumber() + 1);
                root = root.next(entryId, desiredPhase);
                roots.put(rootId, root);
            }

            OperationCenterEntry previous = entries.get(root.currentEntryId());
            String effectiveTitle = phaseTitle(root.rootTitle(), root.rootKind(), root.phase());
            String effectiveCollection = normalizeCollection(collectionId,
                    previous == null ? root.collectionId() : previous.collectionId());
            OperationKind effectiveKind = phaseKind(root.rootKind(), root.phase(), progress.stage());
            Instant startedAt = previous == null ? now : previous.startedAt();
            Instant finishedAt = terminal(progress.stage()) ? now : null;
            String previousError = previous == null ? "" : previous.errorMessage();

            entries.put(root.currentEntryId(), new OperationCenterEntry(
                    root.currentEntryId(), effectiveTitle, effectiveCollection, effectiveKind, progress.stage(),
                    progress.processed(), progress.total(), progress.inserted(), progress.updated(), progress.deleted(),
                    progress.skipped(), progress.duplicates(), progress.warnings(), progress.errors(), progress.currentItem(),
                    progress.cancellable() && !terminal(progress.stage()), startedAt, now, finishedAt, previousError));

            if (terminal(progress.stage())) roots.remove(rootId);
            trimHistoryLocked();
        }
        publishSnapshot();
    }

    public void complete(String operationId, String detail) {
        transition(operationId, OperationStage.COMPLETED, detail, null);
    }

    public void cancel(String operationId, String detail) {
        transition(operationId, OperationStage.CANCELLED, detail, null);
    }

    public void fail(String operationId, Throwable error) {
        String message = rootMessage(error);
        transition(operationId, OperationStage.FAILED, message, message);
    }

    public void fail(String operationId, String message) {
        String effective = message == null || message.isBlank() ? "Невідома помилка" : message.trim();
        transition(operationId, OperationStage.FAILED, effective, effective);
    }

    public List<OperationCenterEntry> snapshot() {
        synchronized (lock) {
            return sortedSnapshotLocked();
        }
    }

    public int activeCount() {
        synchronized (lock) {
            int count = 0;
            for (OperationCenterEntry entry : entries.values()) if (entry.active()) count++;
            return count;
        }
    }

    /** Returns whether an active operation of the requested UI category is already represented. */
    public boolean hasActiveKind(OperationKind kind) {
        if (kind == null) return false;
        synchronized (lock) {
            for (OperationCenterEntry entry : entries.values()) {
                if (entry.active() && entry.kind() == kind) return true;
            }
            return false;
        }
    }

    public int historyCount() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public void clearCompleted() {
        synchronized (lock) {
            entries.entrySet().removeIf(entry -> !entry.getValue().active());
        }
        publishSnapshot();
    }

    /** Registers a listener and immediately publishes the current snapshot. */
    public AutoCloseable addListener(Consumer<List<OperationCenterEntry>> listener) {
        if (listener == null) return () -> { };
        listeners.add(listener);
        listener.accept(snapshot());
        return () -> listeners.remove(listener);
    }

    private void transition(String operationId, OperationStage stage, String detail, String errorMessage) {
        if (operationId == null || operationId.isBlank()) return;
        Instant now = Instant.now();
        synchronized (lock) {
            RootOperationState root = roots.get(operationId);
            String entryId = root == null ? operationId : root.currentEntryId();
            OperationCenterEntry previous = entries.get(entryId);
            if (previous == null) return;
            entries.put(entryId, new OperationCenterEntry(
                    previous.operationId(), previous.title(), previous.collectionId(), previous.kind(), stage,
                    previous.processed(), previous.total(), previous.inserted(), previous.updated(), previous.deleted(),
                    previous.skipped(), previous.duplicates(), previous.warnings(), previous.errors(),
                    detail == null || detail.isBlank() ? previous.currentItem() : detail,
                    false, previous.startedAt(), now, now,
                    errorMessage == null ? previous.errorMessage() : errorMessage));
            roots.remove(operationId);
            trimHistoryLocked();
        }
        publishSnapshot();
    }

    private void closePhaseLocked(String entryId, OperationKind rootKind, Instant now) {
        OperationCenterEntry previous = entries.get(entryId);
        if (previous == null || !previous.active()) return;
        String title = previous.title();
        if (previous.kind() == rootKind && (rootKind == OperationKind.CATALOG_UPDATE || rootKind == OperationKind.CATALOG_IMPORT)) {
            title = title + " · основний етап";
        }
        entries.put(entryId, new OperationCenterEntry(
                previous.operationId(), title, previous.collectionId(), previous.kind(), OperationStage.COMPLETED,
                previous.processed(), previous.total(), previous.inserted(), previous.updated(), previous.deleted(),
                previous.skipped(), previous.duplicates(), previous.warnings(), previous.errors(),
                PHASE_FINISHED_DETAIL, false, previous.startedAt(), now, now, previous.errorMessage()));
    }

    private String nextSegmentId(String rootId, int segmentNumber) {
        String candidate = rootId + "::" + segmentNumber;
        int suffix = segmentNumber;
        while (entries.containsKey(candidate)) candidate = rootId + "::" + (++suffix);
        return candidate;
    }

    private void publishSnapshot() {
        List<OperationCenterEntry> snapshot = snapshot();
        for (Consumer<List<OperationCenterEntry>> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // A broken UI subscriber must not break an application/background operation.
            }
        }
    }

    private List<OperationCenterEntry> sortedSnapshotLocked() {
        ArrayList<OperationCenterEntry> result = new ArrayList<>(entries.values());
        result.sort(Comparator
                .comparing(OperationCenterEntry::active).reversed()
                .thenComparing(OperationCenterEntry::updatedAt, Comparator.reverseOrder()));
        return List.copyOf(result);
    }

    private void trimHistoryLocked() {
        if (entries.size() <= MAX_HISTORY) return;
        List<Map.Entry<String, OperationCenterEntry>> removable = entries.entrySet().stream()
                .filter(entry -> !entry.getValue().active())
                .sorted(Comparator.comparing(entry -> entry.getValue().updatedAt()))
                .toList();
        int remove = entries.size() - MAX_HISTORY;
        for (int i = 0; i < removable.size() && remove > 0; i++, remove--) {
            entries.remove(removable.get(i).getKey());
        }
    }

    private static OperationPhase phaseFor(OperationKind rootKind, OperationStage stage, OperationPhase previousPhase) {
        if (stage == OperationStage.UPDATING_SEARCH_INDEX && rootKind != OperationKind.INDEX_REBUILD) {
            return OperationPhase.SEARCH_INDEX;
        }
        if (stage == OperationStage.REFRESHING_STATISTICS) return OperationPhase.STATISTICS;
        if (stage == OperationStage.ROLLING_BACK) return OperationPhase.ROLLBACK;
        if (stage == OperationStage.FINALIZING && previousPhase != null && previousPhase != OperationPhase.MAIN) {
            return OperationPhase.FINALIZATION;
        }
        return OperationPhase.MAIN;
    }

    private static OperationKind phaseKind(OperationKind rootKind, OperationPhase phase, OperationStage stage) {
        return switch (phase) {
            case SEARCH_INDEX -> OperationKind.INDEX_REBUILD;
            case STATISTICS, ROLLBACK, FINALIZATION -> OperationKind.MAINTENANCE;
            case MAIN -> rootKind != null ? rootKind : inferKind(null, null, stage);
        };
    }

    private static String phaseTitle(String rootTitle, OperationKind rootKind, OperationPhase phase) {
        return switch (phase) {
            case MAIN -> rootTitle;
            case SEARCH_INDEX -> "Оновлення пошукового індексу";
            case STATISTICS -> "Оновлення статистики бібліотеки";
            case ROLLBACK -> "Відновлення попереднього стану";
            case FINALIZATION -> switch (rootKind) {
                case CATALOG_UPDATE -> "Завершення оновлення каталогу";
                case CATALOG_IMPORT -> "Завершення імпорту каталогу";
                default -> "Завершення фонової операції";
            };
        };
    }

    private static String normalizeTitle(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeCollection(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value.trim();
    }

    private static OperationKind inferKind(String operationId, String title, OperationStage stage) {
        String id = operationId == null ? "" : operationId.toLowerCase(java.util.Locale.ROOT);
        String text = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        if (stage == OperationStage.CREATING_COLLECTION || text.contains("створення колекц")) return OperationKind.COLLECTION_CREATE;
        if (stage == OperationStage.DELETING_COLLECTION || text.contains("видалення колекц")) return OperationKind.COLLECTION_DELETE;
        if (id.startsWith("catalog-update-") || text.contains("оновлення каталог") || text.contains("автооновлення каталог")) return OperationKind.CATALOG_UPDATE;
        if (id.startsWith("file-import-") || id.startsWith("directory-import-") || id.startsWith("catalog-import-")
                || id.startsWith("legacy-import-") || text.contains("імпорт")) return OperationKind.CATALOG_IMPORT;
        if (stage == OperationStage.UPDATING_SEARCH_INDEX || text.contains("lucene") || text.contains("індекс")) return OperationKind.INDEX_REBUILD;
        if (stage == OperationStage.BACKING_UP || text.contains("backup") || text.contains("резерв")) return OperationKind.BACKUP;
        if (stage == OperationStage.RESTORING || text.contains("відновлення")) return OperationKind.RESTORE;
        if (stage == OperationStage.INTEGRITY_CHECKS || text.contains("цілісн")) return OperationKind.INTEGRITY_CHECK;
        if (stage == OperationStage.BOOK_DOWNLOAD) return OperationKind.BOOK_DOWNLOAD;
        if (stage == OperationStage.CONVERTING || text.contains("конвертац")) return OperationKind.BOOK_CONVERSION;
        if (stage == OperationStage.SYNCHRONIZING_FILES || stage == OperationStage.OPTIMIZING_DATABASE
                || stage == OperationStage.REFRESHING_STATISTICS || stage == OperationStage.ROLLING_BACK
                || stage == OperationStage.FINALIZING) return OperationKind.MAINTENANCE;
        return OperationKind.GENERIC;
    }

    private static boolean terminal(OperationStage stage) {
        return stage == OperationStage.COMPLETED || stage == OperationStage.CANCELLED || stage == OperationStage.FAILED;
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable == null) return "Невідома помилка";
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private enum OperationPhase {
        MAIN,
        SEARCH_INDEX,
        STATISTICS,
        ROLLBACK,
        FINALIZATION
    }

    private record RootOperationState(
            String rootId,
            String currentEntryId,
            String rootTitle,
            String collectionId,
            OperationKind rootKind,
            OperationPhase phase,
            int segmentNumber
    ) {
        RootOperationState next(String entryId, OperationPhase nextPhase) {
            return new RootOperationState(rootId, entryId, rootTitle, collectionId, rootKind, nextPhase, segmentNumber + 1);
        }
    }
}
