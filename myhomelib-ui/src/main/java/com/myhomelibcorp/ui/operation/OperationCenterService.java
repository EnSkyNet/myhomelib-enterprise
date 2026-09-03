package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 */
@Component
public class OperationCenterService {
    private static final int MAX_HISTORY = 100;

    private final Object lock = new Object();
    private final LinkedHashMap<String, OperationCenterEntry> entries = new LinkedHashMap<>();
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
            OperationCenterEntry previous = entries.get(progress.operationId());
            Instant startedAt = previous == null ? now : previous.startedAt();
            String effectiveTitle = title == null || title.isBlank()
                    ? (previous == null ? "Операція" : previous.title())
                    : title;
            String effectiveCollection = collectionId == null || collectionId.isBlank()
                    ? (previous == null ? "" : previous.collectionId())
                    : collectionId;
            Instant finishedAt = terminal(progress.stage()) ? now : null;
            String previousError = previous == null ? "" : previous.errorMessage();
            OperationKind effectiveKind = kind != null
                    ? kind
                    : previous != null ? previous.kind() : inferKind(progress.operationId(), effectiveTitle, progress.stage());

            entries.put(progress.operationId(), new OperationCenterEntry(
                    progress.operationId(), effectiveTitle, effectiveCollection, effectiveKind, progress.stage(),
                    progress.processed(), progress.total(), progress.inserted(), progress.updated(), progress.deleted(),
                    progress.skipped(), progress.duplicates(), progress.warnings(), progress.errors(), progress.currentItem(),
                    progress.cancellable(), startedAt, now, finishedAt, previousError));
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
            OperationCenterEntry previous = entries.get(operationId);
            if (previous == null) return;
            entries.put(operationId, new OperationCenterEntry(
                    previous.operationId(), previous.title(), previous.collectionId(), previous.kind(), stage,
                    previous.processed(), previous.total(), previous.inserted(), previous.updated(), previous.deleted(),
                    previous.skipped(), previous.duplicates(), previous.warnings(), previous.errors(),
                    detail == null || detail.isBlank() ? previous.currentItem() : detail,
                    false, previous.startedAt(), now, now,
                    errorMessage == null ? previous.errorMessage() : errorMessage));
        }
        publishSnapshot();
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
}
