package com.myhomelibcorp.infrastructure.collection.monitor;

import com.myhomelibcorp.application.collection.CollectionSourceState;
import com.myhomelibcorp.application.event.CollectionSourceUpdateAvailableEvent;
import com.myhomelibcorp.application.port.out.collection.CollectionSourceMonitorPort;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import com.myhomelibcorp.shared.util.Sha256Support;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches only the parent directory of an explicitly configured source file.
 * Events for unrelated files are ignored and matching events are debounced before hashing.
 */
@Component
@Slf4j
public class CollectionSourceMonitorAdapter implements CollectionSourceMonitorPort {
    private static final int MIN_DEBOUNCE_SECONDS = 1;
    private static final int MAX_DEBOUNCE_SECONDS = 3600;

    private final JdbcTemplate metadataJdbcTemplate;
    private final DomainEventPublisher eventPublisher;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "collection-source-debounce");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, WatchRegistration> watchers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingChecks = new ConcurrentHashMap<>();

    public CollectionSourceMonitorAdapter(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate,
            @Qualifier("flywayMetadata") Flyway flywayMetadata,
            DomainEventPublisher eventPublisher) {
        this.metadataJdbcTemplate = metadataJdbcTemplate;
        // Constructor dependency deliberately forces metadata Flyway migration before watch-table access.
        java.util.Objects.requireNonNull(flywayMetadata, "flywayMetadata");
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void startConfiguredMonitors() {
        List<String> ids = metadataJdbcTemplate.query(
                "SELECT collection_id FROM collection_source_watch WHERE enabled = 1",
                (rs, rowNum) -> rs.getString(1));
        ids.forEach(id -> {
            try {
                startMonitoring(id);
            } catch (Exception e) {
                log.warn("Cannot start source monitor for collection {}: {}", id, e.getMessage());
            }
        });
    }

    @Override
    public Optional<CollectionSourceState> findState(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return Optional.empty();
        List<CollectionSourceState> states = metadataJdbcTemplate.query("""
                SELECT collection_id, source_file, enabled, debounce_seconds,
                       baseline_fingerprint, observed_fingerprint, last_checked_at,
                       update_available, last_status
                  FROM collection_source_watch
                 WHERE collection_id = ?
                """, (rs, rowNum) -> mapState(
                        rs.getString("collection_id"),
                        rs.getString("source_file"),
                        rs.getInt("enabled") != 0,
                        rs.getInt("debounce_seconds"),
                        rs.getString("baseline_fingerprint"),
                        rs.getString("observed_fingerprint"),
                        rs.getString("last_checked_at"),
                        rs.getInt("update_available") != 0,
                        rs.getString("last_status")), collectionId);
        return states.stream().findFirst();
    }

    @Override
    public CollectionSourceState configure(String collectionId, Path sourceFile, boolean enabled, int debounceSeconds) {
        requireCollection(collectionId);
        if (sourceFile == null) throw new IllegalArgumentException("Source file cannot be null");
        Path normalized = sourceFile.toAbsolutePath().normalize();
        int debounce = Math.max(MIN_DEBOUNCE_SECONDS, Math.min(MAX_DEBOUNCE_SECONDS, debounceSeconds));

        Optional<CollectionSourceState> previous = findState(collectionId);
        boolean sameSource = previous.map(CollectionSourceState::sourceFile)
                .map(p -> p.toAbsolutePath().normalize().equals(normalized)).orElse(false);

        FingerprintResult initial = inspect(normalized);
        String baseline = sameSource ? previous.map(CollectionSourceState::baselineFingerprint).orElse(null)
                : initial.fingerprint();
        if (baseline == null && initial.fingerprint() != null) baseline = initial.fingerprint();
        String observed = initial.fingerprint();
        boolean available = baseline != null && observed != null && !baseline.equals(observed);

        metadataJdbcTemplate.update("""
                INSERT INTO collection_source_watch(
                    collection_id, source_file, enabled, debounce_seconds,
                    baseline_fingerprint, observed_fingerprint, last_checked_at,
                    update_available, last_status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(collection_id) DO UPDATE SET
                    source_file=excluded.source_file,
                    enabled=excluded.enabled,
                    debounce_seconds=excluded.debounce_seconds,
                    baseline_fingerprint=excluded.baseline_fingerprint,
                    observed_fingerprint=excluded.observed_fingerprint,
                    last_checked_at=excluded.last_checked_at,
                    update_available=excluded.update_available,
                    last_status=excluded.last_status,
                    updated_at=excluded.updated_at
                """, collectionId, normalized.toString(), enabled ? 1 : 0, debounce,
                baseline, observed, Instant.now().toString(), available ? 1 : 0,
                initial.status(), Instant.now().toString());

        stopMonitoring(collectionId);
        if (enabled) startMonitoring(collectionId);
        return findState(collectionId).orElseThrow();
    }

    @Override
    public CollectionSourceState checkNow(String collectionId) {
        CollectionSourceState before = findState(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Collection source is not configured: " + collectionId));
        FingerprintResult inspected = inspect(before.sourceFile());
        boolean available = before.baselineFingerprint() != null && inspected.fingerprint() != null
                && !before.baselineFingerprint().equals(inspected.fingerprint());
        boolean newFingerprint = inspected.fingerprint() != null
                && !inspected.fingerprint().equals(before.observedFingerprint());

        metadataJdbcTemplate.update("""
                UPDATE collection_source_watch
                   SET observed_fingerprint=?, last_checked_at=?, update_available=?,
                       last_status=?, updated_at=?
                 WHERE collection_id=?
                """, inspected.fingerprint(), Instant.now().toString(), available ? 1 : 0,
                inspected.status(), Instant.now().toString(), collectionId);

        CollectionSourceState after = findState(collectionId).orElseThrow();
        if (available && newFingerprint) {
            eventPublisher.publish(new CollectionSourceUpdateAvailableEvent(
                    collectionId, after.sourceFile(), after.observedFingerprint()));
        }
        return after;
    }

    @Override
    public CollectionSourceState markApplied(String collectionId, Path importedSource) {
        CollectionSourceState state = findState(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Collection source is not configured: " + collectionId));
        if (importedSource == null || !samePath(state.sourceFile(), importedSource)) {
            return state;
        }
        FingerprintResult inspected = inspect(state.sourceFile());
        if (inspected.fingerprint() == null) return state;
        metadataJdbcTemplate.update("""
                UPDATE collection_source_watch
                   SET baseline_fingerprint=?, observed_fingerprint=?, last_checked_at=?,
                       update_available=0, last_status=?, updated_at=?
                 WHERE collection_id=?
                """, inspected.fingerprint(), inspected.fingerprint(), Instant.now().toString(),
                "APPLIED", Instant.now().toString(), collectionId);
        return findState(collectionId).orElseThrow();
    }

    @Override
    public synchronized void startMonitoring(String collectionId) {
        stopMonitoring(collectionId);
        CollectionSourceState state = findState(collectionId).orElse(null);
        if (state == null || !state.enabled() || state.sourceFile() == null) return;
        Path parent = state.sourceFile().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            updateStatus(collectionId, "SOURCE_DIRECTORY_MISSING");
            return;
        }
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            parent.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            WatchRegistration registration = new WatchRegistration(collectionId, state.sourceFile(), watchService);
            watchers.put(collectionId, registration);
            Thread thread = new Thread(() -> watchLoop(registration), "collection-source-watch-" + collectionId);
            thread.setDaemon(true);
            registration.thread = thread;
            thread.start();
            log.info("Watching collection source {} for collection {}", state.sourceFile(), collectionId);
        } catch (IOException e) {
            updateStatus(collectionId, "WATCH_ERROR: " + safeMessage(e));
            log.warn("Cannot watch source {}: {}", state.sourceFile(), e.getMessage());
        }
    }

    @Override
    public synchronized void stopMonitoring(String collectionId) {
        ScheduledFuture<?> pending = pendingChecks.remove(collectionId);
        if (pending != null) pending.cancel(false);
        WatchRegistration registration = watchers.remove(collectionId);
        if (registration != null) registration.close();
    }

    private void watchLoop(WatchRegistration registration) {
        while (!registration.closed.get()) {
            try {
                WatchKey key = registration.watchService.take();
                boolean sourceTouched = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == OVERFLOW) continue;
                    Object context = event.context();
                    if (context instanceof Path changed
                            && changed.getFileName().equals(registration.sourceFile.getFileName())) {
                        sourceTouched = true;
                    }
                }
                boolean valid = key.reset();
                if (sourceTouched) scheduleDebouncedCheck(registration.collectionId);
                if (!valid) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                log.warn("Collection source watcher failed for {}: {}", registration.collectionId, e.getMessage());
            }
        }
    }

    private void scheduleDebouncedCheck(String collectionId) {
        CollectionSourceState state = findState(collectionId).orElse(null);
        if (state == null || !state.enabled()) return;
        pendingChecks.compute(collectionId, (id, old) -> {
            if (old != null) old.cancel(false);
            return scheduler.schedule(() -> {
                pendingChecks.remove(collectionId);
                try {
                    checkNow(collectionId);
                } catch (Exception e) {
                    log.warn("Debounced collection source check failed for {}: {}", collectionId, e.getMessage());
                }
            }, Math.max(MIN_DEBOUNCE_SECONDS, state.debounceSeconds()), TimeUnit.SECONDS);
        });
    }

    private FingerprintResult inspect(Path source) {
        try {
            if (source == null) return new FingerprintResult(null, "NOT_CONFIGURED");
            if (!Files.isRegularFile(source)) return new FingerprintResult(null, "SOURCE_MISSING");
            if (!Files.isReadable(source)) return new FingerprintResult(null, "SOURCE_NOT_READABLE");
            String lower = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".zip") || lower.endsWith(".inpx")) {
                try (ZipFile zip = new ZipFile(source.toFile())) {
                    if (!zip.entries().hasMoreElements()) return new FingerprintResult(null, "SOURCE_ARCHIVE_EMPTY");
                } catch (Exception e) {
                    return new FingerprintResult(null, "SOURCE_ARCHIVE_INVALID: " + safeMessage(e));
                }
            }
            return new FingerprintResult(Sha256Support.file(source), "READY");
        } catch (Exception e) {
            return new FingerprintResult(null, "SOURCE_ERROR: " + safeMessage(e));
        }
    }

    private void requireCollection(String collectionId) {
        Integer count = metadataJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collections WHERE id=?", Integer.class, collectionId);
        if (count == null || count == 0) throw new IllegalArgumentException("Unknown collection: " + collectionId);
    }

    private void updateStatus(String collectionId, String status) {
        metadataJdbcTemplate.update(
                "UPDATE collection_source_watch SET last_status=?, updated_at=? WHERE collection_id=?",
                status, Instant.now().toString(), collectionId);
    }

    private CollectionSourceState mapState(String id, String path, boolean enabled, int debounce,
                                           String baseline, String observed, String checked,
                                           boolean available, String status) {
        Instant instant = null;
        if (checked != null && !checked.isBlank()) {
            try { instant = Instant.parse(checked); } catch (Exception ignored) { }
        }
        return new CollectionSourceState(id, path == null ? null : Paths.get(path), enabled, debounce,
                baseline, observed, instant, available, status == null ? "UNKNOWN" : status);
    }

    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) return false;
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @PreDestroy
    void shutdown() {
        watchers.keySet().stream().toList().forEach(this::stopMonitoring);
        scheduler.shutdownNow();
    }

    private record FingerprintResult(String fingerprint, String status) { }

    private static final class WatchRegistration implements AutoCloseable {
        private final String collectionId;
        private final Path sourceFile;
        private final WatchService watchService;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Thread thread;

        private WatchRegistration(String collectionId, Path sourceFile, WatchService watchService) {
            this.collectionId = collectionId;
            this.sourceFile = sourceFile;
            this.watchService = watchService;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { watchService.close(); } catch (IOException ignored) { }
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }
}
