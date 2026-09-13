package com.myhomelibcorp.application.usecase.duplicate;

import com.myhomelibcorp.application.duplicate.ArtifactScanCandidate;
import com.myhomelibcorp.application.duplicate.ExactDuplicateGroup;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanSession;
import com.myhomelibcorp.application.port.out.duplicate.ExactDuplicateScanStore;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("performance")
class ExactDuplicateScannerBenchmarkTest {
    private static final int ARTIFACTS = 10_000;
    private static final byte[] PAYLOAD = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);

    @Test
    void hashesTenThousandSmallArtifactsWithDocumentedThroughput() {
        List<ArtifactScanCandidate> candidates = new ArrayList<>(ARTIFACTS);
        for (int i = 0; i < ARTIFACTS; i++) {
            String uuid = UUID.nameUUIDFromBytes(("book-" + i).getBytes(StandardCharsets.UTF_8)).toString();
            candidates.add(new ArtifactScanCandidate(BookId.fromString(uuid), "artifact-" + i, "fb2",
                    new BookFile("book-" + i + ".fb2", "", "", PAYLOAD.length, "/benchmark")));
        }
        BenchmarkStore store = new BenchmarkStore(candidates);
        BookResourcePort resources = mock(BookResourcePort.class);
        when(resources.readBookData(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(ignored -> Optional.of(new ByteArrayInputStream(PAYLOAD)));
        ExactDuplicateScanner scanner = new ExactDuplicateScanner(store, resources, new ImmediateExecutor());

        long start = System.nanoTime();
        var result = scanner.scanAsync(null).join();
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        double throughput = ARTIFACTS / Math.max(seconds, 0.001);
        System.out.printf("MHL-104 benchmark: %,d artifacts, %.3f s, %.0f artifacts/s%n", ARTIFACTS, seconds, throughput);

        assertThat(result.hashed()).isEqualTo(ARTIFACTS);
        assertThat(result.skipped()).isZero();
        // Intentionally generous regression floor: this protects against accidental O(n^2)
        // behavior without making the test sensitive to ordinary CI variance.
        assertThat(throughput).isGreaterThan(1_000.0);
    }

    private static final class BenchmarkStore implements ExactDuplicateScanStore {
        private final List<ArtifactScanCandidate> candidates;
        private int cursor;
        private long bytes;
        BenchmarkStore(List<ArtifactScanCandidate> candidates) { this.candidates = candidates; }
        @Override public ExactDuplicateScanSession resumeOrStart() { return session(); }
        @Override public List<ArtifactScanCandidate> nextBatch(String scanId, int limit) {
            if (cursor >= candidates.size()) return List.of();
            return candidates.subList(cursor, Math.min(candidates.size(), cursor + limit));
        }
        @Override public ExactDuplicateScanSession markHashed(String scanId, String artifactId, String sha256, long sizeBytes) {
            cursor++;
            bytes += sizeBytes;
            return session();
        }
        @Override public ExactDuplicateScanSession markSkipped(String scanId, String artifactId, String reason) {
            cursor++;
            return session();
        }
        @Override public ExactDuplicateScanSession pause(String scanId) { return session(); }
        @Override public ExactDuplicateScanSession complete(String scanId) { return session(); }
        @Override public List<ExactDuplicateGroup> findDuplicateGroups() { return List.of(); }
        private ExactDuplicateScanSession session() {
            return new ExactDuplicateScanSession("benchmark-scan", candidates.size(), cursor, cursor, 0, bytes, false);
        }
    }

    private static final class ImmediateExecutor implements ExecutorPort {
        @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
            try { return CompletableFuture.completedFuture(task.call()); }
            catch (Exception e) { return CompletableFuture.failedFuture(e); }
        }
        @Override public void execute(Runnable task) { task.run(); }
    }
}
