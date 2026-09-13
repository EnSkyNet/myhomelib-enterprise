package com.myhomelibcorp.application.usecase.duplicate;

import com.myhomelibcorp.application.duplicate.ArtifactScanCandidate;
import com.myhomelibcorp.application.duplicate.ExactDuplicateGroup;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanResult;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanSession;
import com.myhomelibcorp.application.port.out.duplicate.ExactDuplicateScanStore;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Computes full-content SHA-256 hashes and groups byte-for-byte identical artifacts.
 * The scan inventory and progress are persisted by the store, so cancellation or a process
 * restart resumes the same snapshot instead of silently skipping newly sorted rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExactDuplicateScanner {
    private static final int BATCH_SIZE = 128;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final ExactDuplicateScanStore store;
    private final BookResourcePort resources;
    private final ExecutorPort executor;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CompletableFuture<ExactDuplicateScanResult> scanAsync(Consumer<OperationProgress> telemetry) {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Exact duplicate scan is already running"));
        }
        cancelRequested.set(false);
        try {
            return executor.submit(() -> scan(telemetry))
                    .whenComplete((ignored, failure) -> running.set(false));
        } catch (RuntimeException failure) {
            running.set(false);
            throw failure;
        }
    }

    public void cancel() {
        cancelRequested.set(true);
    }

    public boolean isRunning() {
        return running.get();
    }

    ExactDuplicateScanResult scan(Consumer<OperationProgress> telemetry) {
        ExactDuplicateScanSession session = store.resumeOrStart();
        boolean resumed = session.resumed();
        emit(telemetry, progress(session, OperationStage.SCANNING_DUPLICATES, ""));
        log.info("Exact duplicate scan {}: total={}, processed={}, resumed={}",
                session.scanId(), session.total(), session.processed(), session.resumed());

        while (session.processed() < session.total()) {
            if (cancelRequested.get()) {
                session = store.pause(session.scanId());
                emit(telemetry, progress(session, OperationStage.CANCELLED, ""));
                return result(session, true, resumed, List.of());
            }

            List<ArtifactScanCandidate> batch = store.nextBatch(session.scanId(), BATCH_SIZE);
            if (batch.isEmpty()) break;

            for (ArtifactScanCandidate candidate : batch) {
                if (cancelRequested.get()) {
                    session = store.pause(session.scanId());
                    emit(telemetry, progress(session, OperationStage.CANCELLED, candidate.artifactId()));
                    return result(session, true, resumed, List.of());
                }
                try {
                    HashValue hash = hash(candidate);
                    session = store.markHashed(session.scanId(), candidate.artifactId(), hash.sha256(), hash.bytes());
                } catch (ScanCancelledIOException cancelled) {
                    session = store.pause(session.scanId());
                    emit(telemetry, progress(session, OperationStage.CANCELLED, candidate.artifactId()));
                    return result(session, true, resumed, List.of());
                } catch (IOException | RuntimeException failure) {
                    log.warn("Skipping unreadable artifact {} during exact duplicate scan: {}",
                            candidate.artifactId(), failure.getMessage());
                    session = store.markSkipped(session.scanId(), candidate.artifactId(), failure.getMessage());
                }
                emit(telemetry, progress(session, OperationStage.SCANNING_DUPLICATES, candidate.artifactId()));
            }
        }

        session = store.complete(session.scanId());
        List<ExactDuplicateGroup> groups = store.findDuplicateGroups();
        emit(telemetry, progress(session, OperationStage.COMPLETED, "")
                .withCounts(0, 0, 0, session.skipped(),
                        groups.stream().mapToLong(g -> g.artifacts().size()).sum(), session.skipped(), 0));
        return result(session, false, resumed, groups);
    }

    private HashValue hash(ArtifactScanCandidate candidate) throws IOException {
        var file = candidate.file();
        try (InputStream input = resources.readBookData(
                file.getFileName(), file.getFolder(), file.getCollectionRoot(), file.getArchiveEntry())
                .orElseThrow(() -> new IOException("Artifact bytes are not available"))) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[BUFFER_SIZE];
            long bytes = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                bytes += read;
                if (cancelRequested.get()) throw new ScanCancelledIOException();
            }
            return new HashValue(HexFormat.of().formatHex(digest.digest()), bytes);
        } catch (ScanCancelledIOException cancelled) {
            throw cancelled;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static OperationProgress progress(ExactDuplicateScanSession session, OperationStage stage, String currentItem) {
        return OperationProgress.stage(session.scanId(), stage, stage == OperationStage.SCANNING_DUPLICATES)
                .withProgress(session.processed(), session.total())
                .withBytes(session.bytesProcessed(), -1)
                .withCounts(0, 0, 0, session.skipped(), 0, session.skipped(), 0)
                .withCurrentItem(currentItem);
    }

    private static ExactDuplicateScanResult result(ExactDuplicateScanSession session, boolean cancelled, boolean resumed,
                                                    List<ExactDuplicateGroup> groups) {
        return new ExactDuplicateScanResult(session.scanId(), session.total(), session.processed(),
                session.hashed(), session.skipped(), session.bytesProcessed(), cancelled, resumed, groups);
    }

    private static void emit(Consumer<OperationProgress> telemetry, OperationProgress progress) {
        if (telemetry != null) telemetry.accept(progress);
    }

    private record HashValue(String sha256, long bytes) {}
    private static final class ScanCancelledIOException extends IOException {}
}
