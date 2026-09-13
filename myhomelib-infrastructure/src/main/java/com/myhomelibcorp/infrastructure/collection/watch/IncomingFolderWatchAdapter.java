package com.myhomelibcorp.infrastructure.collection.watch;

import com.myhomelibcorp.application.event.IncomingFolderFileReadyEvent;
import com.myhomelibcorp.application.folderwatch.IncomingFolderCandidate;
import com.myhomelibcorp.application.folderwatch.IncomingFolderCandidateStatus;
import com.myhomelibcorp.application.folderwatch.IncomingFolderWatchState;
import com.myhomelibcorp.application.port.out.collection.IncomingFolderWatchPort;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.*;

/** MHL-113 WatchService adapter with persisted debounce/stability state and content deduplication. */
@Component
@Slf4j
public class IncomingFolderWatchAdapter implements IncomingFolderWatchPort {
    private static final int MIN_SECONDS = 1;
    private static final int MAX_SECONDS = 3600;
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();

    private final JdbcTemplate jdbc;
    private final DomainEventPublisher events;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "incoming-folder-watch-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, WatchRegistration> watchers = new ConcurrentHashMap<>();
    private final Map<String, PendingTask> pending = new ConcurrentHashMap<>();

    public IncomingFolderWatchAdapter(@Qualifier("metadataJdbcTemplate") JdbcTemplate jdbc,
                                      @Qualifier("flywayMetadata") Flyway flywayMetadata,
                                      DomainEventPublisher events) {
        this.jdbc = jdbc;
        java.util.Objects.requireNonNull(flywayMetadata, "flywayMetadata");
        this.events = events;
    }

    @PostConstruct
    void startConfigured() {
        // Crash/restart safety: a claimed file is replayable until markImported commits.
        jdbc.update("UPDATE incoming_folder_file SET status='READY', updated_at=? WHERE status='PROCESSING'", now());
        List<String> enabled = jdbc.query(
                "SELECT collection_id FROM incoming_folder_watch WHERE enabled=1",
                (rs, rowNum) -> rs.getString(1));
        for (String collectionId : enabled) {
            try {
                startMonitoring(collectionId);
                scanNow(collectionId);
            } catch (RuntimeException failure) {
                log.warn("Cannot resume incoming-folder watcher for {}: {}", collectionId, safeMessage(failure));
            }
        }
    }

    @Override
    public Optional<IncomingFolderWatchState> findState(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return Optional.empty();
        List<IncomingFolderWatchState> rows = jdbc.query("""
                SELECT w.collection_id, w.folder_path, w.enabled, w.debounce_seconds, w.stability_seconds,
                       w.last_scan_at, w.last_status,
                       SUM(CASE WHEN f.status='WAITING' THEN 1 ELSE 0 END) waiting_count,
                       SUM(CASE WHEN f.status='READY' THEN 1 ELSE 0 END) ready_count,
                       SUM(CASE WHEN f.status='PROCESSING' THEN 1 ELSE 0 END) processing_count,
                       SUM(CASE WHEN f.status='IMPORTED' THEN 1 ELSE 0 END) imported_count,
                       SUM(CASE WHEN f.status='DUPLICATE_CONTENT' THEN 1 ELSE 0 END) duplicate_content_count,
                       SUM(CASE WHEN f.status='FAILED' THEN 1 ELSE 0 END) failed_count
                  FROM incoming_folder_watch w
             LEFT JOIN incoming_folder_file f ON f.collection_id=w.collection_id
                 WHERE w.collection_id=?
              GROUP BY w.collection_id, w.folder_path, w.enabled, w.debounce_seconds, w.stability_seconds,
                       w.last_scan_at, w.last_status
                """, (rs, rowNum) -> new IncomingFolderWatchState(
                rs.getString("collection_id"), Path.of(rs.getString("folder_path")), rs.getInt("enabled") != 0,
                rs.getInt("debounce_seconds"), rs.getInt("stability_seconds"), parseInstant(rs.getString("last_scan_at")),
                rs.getString("last_status"), rs.getInt("waiting_count"), rs.getInt("ready_count"),
                rs.getInt("processing_count"), rs.getInt("imported_count"), rs.getInt("duplicate_content_count"),
                rs.getInt("failed_count")), collectionId);
        return rows.stream().findFirst();
    }

    @Override
    public IncomingFolderWatchState configure(String collectionId, Path folder, boolean enabled,
                                               int debounceSeconds, int stabilitySeconds) {
        requireCollection(collectionId);
        if (folder == null) throw new IllegalArgumentException("Incoming folder cannot be null");
        Path normalized = folder.toAbsolutePath().normalize();
        int debounce = clamp(debounceSeconds);
        int stability = clamp(stabilitySeconds);
        Optional<IncomingFolderWatchState> previous = findState(collectionId);
        boolean sameFolder = previous.map(IncomingFolderWatchState::folder)
                .map(path -> path.toAbsolutePath().normalize().equals(normalized)).orElse(false);
        if (!sameFolder) jdbc.update("DELETE FROM incoming_folder_file WHERE collection_id=?", collectionId);
        String status = Files.isDirectory(normalized) ? "CONFIGURED" : "FOLDER_MISSING";
        jdbc.update("""
                INSERT INTO incoming_folder_watch(collection_id, folder_path, enabled, debounce_seconds,
                                                  stability_seconds, last_status, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(collection_id) DO UPDATE SET
                    folder_path=excluded.folder_path,
                    enabled=excluded.enabled,
                    debounce_seconds=excluded.debounce_seconds,
                    stability_seconds=excluded.stability_seconds,
                    last_status=excluded.last_status,
                    updated_at=excluded.updated_at
                """, collectionId, normalized.toString(), enabled ? 1 : 0, debounce, stability, status, now());
        stopMonitoring(collectionId);
        if (enabled) {
            startMonitoring(collectionId);
            scanNow(collectionId);
        }
        return findState(collectionId).orElseThrow();
    }

    @Override
    public IncomingFolderWatchState scanNow(String collectionId) {
        IncomingFolderWatchState state = findState(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Incoming folder is not configured: " + collectionId));
        Path folder = state.folder();
        if (folder == null || !Files.isDirectory(folder)) {
            updateWatchStatus(collectionId, "FOLDER_MISSING", true);
            return findState(collectionId).orElseThrow();
        }
        int scheduled = 0;
        try (var stream = Files.list(folder)) {
            for (Path file : stream.filter(Files::isRegularFile).filter(IncomingFolderWatchAdapter::isIncomingSupported).toList()) {
                scheduleCandidate(collectionId, file, 0);
                scheduled++;
            }
        } catch (IOException failure) {
            updateWatchStatus(collectionId, "SCAN_ERROR: " + safeMessage(failure), true);
            throw new IllegalStateException("Cannot scan incoming folder", failure);
        }
        updateWatchStatus(collectionId, "SCAN_SCHEDULED:" + scheduled, true);
        return findState(collectionId).orElseThrow();
    }

    @Override
    public List<IncomingFolderCandidate> listReady(String collectionId, int limit) {
        int safeLimit = Math.max(1, Math.min(250, limit));
        return jdbc.query("""
                SELECT collection_id, file_path, fingerprint, observed_size, observed_mtime,
                       status, detected_at, last_error
                  FROM incoming_folder_file
                 WHERE collection_id=? AND status='READY' AND fingerprint IS NOT NULL
                 ORDER BY datetime(detected_at), file_path
                 LIMIT ?
                """, (rs, rowNum) -> mapCandidate(
                rs.getString("collection_id"), rs.getString("file_path"), rs.getString("fingerprint"),
                rs.getLong("observed_size"), rs.getLong("observed_mtime"), rs.getString("status"),
                rs.getString("detected_at"), rs.getString("last_error")), collectionId, safeLimit);
    }

    @Override
    public boolean claimReady(String collectionId, Path file, String fingerprint) {
        if (file == null || fingerprint == null || fingerprint.isBlank()) return false;
        return jdbc.update("""
                UPDATE incoming_folder_file SET status='PROCESSING', last_error=NULL, updated_at=?
                 WHERE collection_id=? AND file_path=? AND fingerprint=? AND status='READY'
                """, now(), collectionId, normalize(file), fingerprint) == 1;
    }

    @Override
    public void markImported(String collectionId, Path file, String fingerprint,
                             long imported, long duplicates, long errors) {
        int changed = jdbc.update("""
                UPDATE incoming_folder_file
                   SET status='IMPORTED', imported_at=?, last_error=NULL,
                       imported_count=?, duplicate_count=?, error_count=?, updated_at=?
                 WHERE collection_id=? AND file_path=? AND fingerprint=? AND status='PROCESSING'
                """, now(), Math.max(0, imported), Math.max(0, duplicates), Math.max(0, errors), now(),
                collectionId, normalize(file), fingerprint);
        if (changed != 1) throw new IllegalStateException("Incoming-folder claim is no longer current: " + file);
    }

    @Override
    public void markFailed(String collectionId, Path file, String fingerprint, String error) {
        jdbc.update("""
                UPDATE incoming_folder_file
                   SET status='FAILED', last_error=?, updated_at=?
                 WHERE collection_id=? AND file_path=? AND fingerprint=? AND status='PROCESSING'
                """, truncate(error), now(), collectionId, normalize(file), fingerprint);
    }

    @Override
    public synchronized void startMonitoring(String collectionId) {
        stopMonitoring(collectionId);
        IncomingFolderWatchState state = findState(collectionId).orElse(null);
        if (state == null || !state.enabled() || state.folder() == null) return;
        if (!Files.isDirectory(state.folder())) {
            updateWatchStatus(collectionId, "FOLDER_MISSING", false);
            return;
        }
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            state.folder().register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            WatchRegistration registration = new WatchRegistration(collectionId, state.folder(), watchService);
            watchers.put(collectionId, registration);
            Thread thread = new Thread(() -> watchLoop(registration), "incoming-folder-watch-" + collectionId);
            thread.setDaemon(true);
            registration.thread = thread;
            thread.start();
            updateWatchStatus(collectionId, "WATCHING", false);
        } catch (IOException failure) {
            updateWatchStatus(collectionId, "WATCH_ERROR: " + safeMessage(failure), false);
            throw new IllegalStateException("Cannot watch incoming folder", failure);
        }
    }

    @Override
    public synchronized void stopMonitoring(String collectionId) {
        String prefix = collectionId + "\u0000";
        pending.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix)) return false;
            entry.getValue().cancel();
            return true;
        });
        WatchRegistration registration = watchers.remove(collectionId);
        if (registration != null) registration.close();
    }

    private void watchLoop(WatchRegistration registration) {
        while (!registration.closed.get()) {
            try {
                WatchKey key = registration.watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == OVERFLOW) {
                        scanNow(registration.collectionId);
                        continue;
                    }
                    if (!(event.context() instanceof Path relative)) continue;
                    Path changed = registration.folder.resolve(relative).toAbsolutePath().normalize();
                    if (event.kind() == ENTRY_DELETE) {
                        markMissing(registration.collectionId, changed);
                    } else if (isIncomingSupported(changed)) {
                        scheduleCandidate(registration.collectionId, changed, -1);
                    }
                }
                if (!key.reset()) break;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException closed) {
                break;
            } catch (RuntimeException failure) {
                log.warn("Incoming folder watcher failed for {}: {}", registration.collectionId, safeMessage(failure));
            }
        }
    }

    private void scheduleCandidate(String collectionId, Path file, int explicitDelaySeconds) {
        IncomingFolderWatchState state = findState(collectionId).orElse(null);
        if (state == null || !state.enabled() && explicitDelaySeconds < 0) return;
        int delay = explicitDelaySeconds >= 0 ? explicitDelaySeconds : state.debounceSeconds();
        String key = key(collectionId, file);
        PendingTask replacement = new PendingTask();
        pending.compute(key, (ignored, old) -> {
            if (old != null) old.cancel();
            replacement.future = scheduler.schedule(() -> {
                AtomicBoolean current = new AtomicBoolean();
                pending.computeIfPresent(key, (ignoredKey, registered) -> {
                    if (registered != replacement) return registered;
                    current.set(true);
                    return null;
                });
                if (!current.get()) return;
                try { observeCandidate(collectionId, file); }
                catch (RuntimeException failure) {
                    log.warn("Incoming candidate check failed for {}: {}", file, safeMessage(failure));
                }
            }, Math.max(0, delay), TimeUnit.SECONDS);
            return replacement;
        });
    }

    private void observeCandidate(String collectionId, Path file) {
        IncomingFolderWatchState state = findState(collectionId).orElse(null);
        if (state == null || file == null) return;
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            markMissing(collectionId, normalized);
            return;
        }
        if (!isIncomingSupported(normalized)) return;
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(normalized, BasicFileAttributes.class);
        } catch (IOException failure) {
            upsertWaiting(collectionId, normalized, 0, 0, now(), "ATTRIBUTE_ERROR: " + safeMessage(failure));
            return;
        }
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().toMillis();
        CandidateObservation old = loadObservation(collectionId, normalized).orElse(null);
        Instant current = Instant.now();
        if (old == null || old.size != size || old.mtime != mtime) {
            upsertWaiting(collectionId, normalized, size, mtime, current.toString(), null);
            scheduleCandidate(collectionId, normalized, state.stabilitySeconds());
            return;
        }
        if (old.status == IncomingFolderCandidateStatus.IMPORTED
                || old.status == IncomingFolderCandidateStatus.DUPLICATE_CONTENT) return;
        Instant signatureSince = parseInstant(old.signatureSeenAt);
        if (signatureSince == null || Duration.between(signatureSince, current).getSeconds() < state.stabilitySeconds()) {
            int remaining = signatureSince == null ? state.stabilitySeconds()
                    : Math.max(1, state.stabilitySeconds() - (int) Duration.between(signatureSince, current).getSeconds());
            scheduleCandidate(collectionId, normalized, remaining);
            return;
        }
        String fingerprint;
        try {
            fingerprint = Sha256Support.file(normalized);
        } catch (Exception failure) {
            jdbc.update("""
                    UPDATE incoming_folder_file SET status='FAILED', last_error=?, updated_at=?
                     WHERE collection_id=? AND file_path=?
                    """, truncate("HASH_ERROR: " + safeMessage(failure)), now(), collectionId, normalize(normalized));
            return;
        }
        if (alreadyImportedFingerprint(collectionId, normalized, fingerprint)) {
            jdbc.update("""
                    UPDATE incoming_folder_file
                       SET fingerprint=?, status='DUPLICATE_CONTENT', last_error=NULL, updated_at=?
                     WHERE collection_id=? AND file_path=?
                    """, fingerprint, now(), collectionId, normalize(normalized));
            return;
        }
        boolean publish = old.status != IncomingFolderCandidateStatus.READY || !fingerprint.equals(old.fingerprint);
        jdbc.update("""
                UPDATE incoming_folder_file
                   SET fingerprint=?, status='READY', last_error=NULL, updated_at=?
                 WHERE collection_id=? AND file_path=?
                """, fingerprint, now(), collectionId, normalize(normalized));
        if (publish) events.publish(new IncomingFolderFileReadyEvent(collectionId, normalized, fingerprint));
    }

    private void upsertWaiting(String collectionId, Path file, long size, long mtime, String signatureSeenAt, String error) {
        jdbc.update("""
                INSERT INTO incoming_folder_file(collection_id, file_path, observed_size, observed_mtime,
                                                 signature_seen_at, fingerprint, status, detected_at,
                                                 last_error, updated_at)
                VALUES(?, ?, ?, ?, ?, NULL, 'WAITING', ?, ?, ?)
                ON CONFLICT(collection_id, file_path) DO UPDATE SET
                    observed_size=excluded.observed_size,
                    observed_mtime=excluded.observed_mtime,
                    signature_seen_at=excluded.signature_seen_at,
                    fingerprint=NULL,
                    status='WAITING',
                    last_error=excluded.last_error,
                    updated_at=excluded.updated_at
                """, collectionId, normalize(file), size, mtime, signatureSeenAt, now(), truncate(error), now());
    }

    private Optional<CandidateObservation> loadObservation(String collectionId, Path file) {
        List<CandidateObservation> rows = jdbc.query("""
                SELECT observed_size, observed_mtime, signature_seen_at, fingerprint, status
                  FROM incoming_folder_file WHERE collection_id=? AND file_path=?
                """, (rs, rowNum) -> new CandidateObservation(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), IncomingFolderCandidateStatus.valueOf(rs.getString(5))),
                collectionId, normalize(file));
        return rows.stream().findFirst();
    }

    private boolean alreadyImportedFingerprint(String collectionId, Path file, String fingerprint) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM incoming_folder_file
                 WHERE collection_id=? AND fingerprint=? AND status='IMPORTED' AND file_path<>?
                """, Integer.class, collectionId, fingerprint, normalize(file));
        return count != null && count > 0;
    }

    private void markMissing(String collectionId, Path file) {
        if (file == null) return;
        jdbc.update("""
                UPDATE incoming_folder_file SET status='MISSING', last_error=NULL, updated_at=?
                 WHERE collection_id=? AND file_path=? AND status<>'IMPORTED'
                """, now(), collectionId, normalize(file));
    }

    private void updateWatchStatus(String collectionId, String status, boolean scanned) {
        if (scanned) {
            jdbc.update("UPDATE incoming_folder_watch SET last_status=?, last_scan_at=?, updated_at=? WHERE collection_id=?",
                    status, now(), now(), collectionId);
        } else {
            jdbc.update("UPDATE incoming_folder_watch SET last_status=?, updated_at=? WHERE collection_id=?",
                    status, now(), collectionId);
        }
    }

    private void requireCollection(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) throw new IllegalArgumentException("collectionId is required");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM collections WHERE id=?", Integer.class, collectionId);
        if (count == null || count == 0) throw new IllegalArgumentException("Unknown collection: " + collectionId);
    }

    private static IncomingFolderCandidate mapCandidate(String collectionId, String path, String fingerprint,
                                                        long size, long mtime, String status, String detectedAt,
                                                        String lastError) {
        return new IncomingFolderCandidate(collectionId, Path.of(path), fingerprint, size, mtime,
                IncomingFolderCandidateStatus.valueOf(status), parseInstant(detectedAt), lastError);
    }

    private static boolean isIncomingSupported(Path file) {
        return FORMATS.detect(file)
                .filter(SupportedFormat::importSupported)
                .map(format -> format.family() != SupportedFormat.Family.CATALOG)
                .orElse(false);
    }

    private static int clamp(int seconds) { return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds)); }
    private static String normalize(Path path) { return path.toAbsolutePath().normalize().toString(); }
    private static String key(String collectionId, Path file) { return collectionId + "\u0000" + normalize(file); }
    private static String now() { return Instant.now().toString(); }
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (RuntimeException ignored) { return null; }
    }
    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
    private static String safeMessage(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @PreDestroy
    void shutdown() {
        watchers.keySet().stream().toList().forEach(this::stopMonitoring);
        scheduler.shutdownNow();
    }

    private record CandidateObservation(long size, long mtime, String signatureSeenAt,
                                        String fingerprint, IncomingFolderCandidateStatus status) { }

    private static final class PendingTask {
        private volatile ScheduledFuture<?> future;

        private void cancel() {
            ScheduledFuture<?> current = future;
            if (current != null) current.cancel(false);
        }
    }

    private static final class WatchRegistration implements AutoCloseable {
        private final String collectionId;
        private final Path folder;
        private final WatchService watchService;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Thread thread;

        private WatchRegistration(String collectionId, Path folder, WatchService watchService) {
            this.collectionId = collectionId;
            this.folder = folder;
            this.watchService = watchService;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { watchService.close(); } catch (IOException ignored) { }
            Thread current = thread;
            if (current != null) current.interrupt();
        }
    }
}
