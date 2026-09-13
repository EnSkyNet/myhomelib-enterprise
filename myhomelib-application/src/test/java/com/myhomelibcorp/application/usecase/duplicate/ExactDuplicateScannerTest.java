package com.myhomelibcorp.application.usecase.duplicate;

import com.myhomelibcorp.application.duplicate.ArtifactScanCandidate;
import com.myhomelibcorp.application.duplicate.ExactDuplicateArtifact;
import com.myhomelibcorp.application.duplicate.ExactDuplicateGroup;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanResult;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanSession;
import com.myhomelibcorp.application.port.out.duplicate.ExactDuplicateScanStore;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExactDuplicateScannerTest {

    @Test
    void fullContentSha256FindsEveryExactDuplicateAndDoesNotMergeDifferentBytes() {
        ArtifactScanCandidate epub = candidate("10000000-0000-0000-0000-000000000001", "a-epub", "same.epub", "epub");
        ArtifactScanCandidate fb2 = candidate("10000000-0000-0000-0000-000000000002", "a-fb2", "same.fb2", "fb2");
        ArtifactScanCandidate pdf = candidate("10000000-0000-0000-0000-000000000003", "a-pdf", "different.pdf", "pdf");
        InMemoryStore store = new InMemoryStore(List.of(epub, fb2, pdf), false);
        BookResourcePort resources = resourcePort(Map.of(
                "same.epub", "byte-for-byte identical",
                "same.fb2", "byte-for-byte identical",
                "different.pdf", "different bytes"));

        ExactDuplicateScanner scanner = new ExactDuplicateScanner(store, resources, new ImmediateExecutor());
        ExactDuplicateScanResult result = scanner.scanAsync(null).join();

        assertThat(result.cancelled()).isFalse();
        assertThat(result.processed()).isEqualTo(3);
        assertThat(result.hashed()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        assertThat(result.groups()).hasSize(1);
        assertThat(result.groups().getFirst().artifacts())
                .extracting(ExactDuplicateArtifact::artifactId)
                .containsExactly("a-epub", "a-fb2");
        assertThat(store.hashes.get("a-epub")).isEqualTo(store.hashes.get("a-fb2"));
        assertThat(store.hashes.get("a-pdf")).isNotEqualTo(store.hashes.get("a-epub"));
    }

    @Test
    void asyncEntryPointDoesNotRunHashingOnCallingThread() throws Exception {
        ArtifactScanCandidate one = candidate("20000000-0000-0000-0000-000000000001", "a-one", "one.fb2", "fb2");
        InMemoryStore store = new InMemoryStore(List.of(one), false);
        ControlledExecutor executor = new ControlledExecutor();
        ExactDuplicateScanner scanner = new ExactDuplicateScanner(store, resourcePort(Map.of("one.fb2", "content")), executor);

        CompletableFuture<ExactDuplicateScanResult> future = scanner.scanAsync(null);

        assertThat(future).isNotDone();
        assertThat(store.hashes).isEmpty();
        executor.runSubmitted();
        assertThat(future.join().hashed()).isEqualTo(1);
    }

    @Test
    void resumedSessionProcessesOnlyPendingSnapshotItems() {
        ArtifactScanCandidate alreadyDone = candidate("30000000-0000-0000-0000-000000000001", "a-done", "done.fb2", "fb2");
        ArtifactScanCandidate pending = candidate("30000000-0000-0000-0000-000000000002", "a-pending", "pending.fb2", "fb2");
        InMemoryStore store = new InMemoryStore(List.of(alreadyDone, pending), true);
        store.hashes.put("a-done", "preexisting-hash");
        store.processed = 1;
        store.hashed = 1;
        store.cursor = 1;

        ExactDuplicateScanner scanner = new ExactDuplicateScanner(store,
                resourcePort(Map.of("pending.fb2", "pending-content")), new ImmediateExecutor());
        ExactDuplicateScanResult result = scanner.scanAsync(null).join();

        assertThat(result.resumed()).isTrue();
        assertThat(result.processed()).isEqualTo(2);
        assertThat(store.hashes.get("a-done")).isEqualTo("preexisting-hash");
        assertThat(store.hashes).containsKey("a-pending");
    }

    @Test
    void cancellationDuringLargeArtifactLeavesItPendingForResume() {
        ArtifactScanCandidate one = candidate("40000000-0000-0000-0000-000000000001", "a-large", "large.fb2", "fb2");
        InMemoryStore store = new InMemoryStore(List.of(one), false);
        ExactDuplicateScanner[] holder = new ExactDuplicateScanner[1];
        BookResourcePort resources = mock(BookResourcePort.class);
        when(resources.readBookData(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation ->
                Optional.of(new InputStream() {
                    int reads;
                    @Override public int read() { return -1; }
                    @Override public int read(byte[] b, int off, int len) {
                        if (reads++ == 0) {
                            java.util.Arrays.fill(b, off, off + Math.min(len, 1024), (byte) 7);
                            holder[0].cancel();
                            return Math.min(len, 1024);
                        }
                        return -1;
                    }
                }));
        holder[0] = new ExactDuplicateScanner(store, resources, new ImmediateExecutor());

        ExactDuplicateScanResult cancelled = holder[0].scanAsync(null).join();

        assertThat(cancelled.cancelled()).isTrue();
        assertThat(cancelled.processed()).isZero();
        assertThat(cancelled.skipped()).isZero();
        assertThat(store.cursor).isZero();
        assertThat(store.hashes).isEmpty();
    }

    private static ArtifactScanCandidate candidate(String bookId, String artifactId, String fileName, String format) {
        return new ArtifactScanCandidate(BookId.fromString(bookId), artifactId, format,
                new BookFile(fileName, "", "", 0, "/library"));
    }

    private static BookResourcePort resourcePort(Map<String, String> contentByFile) {
        BookResourcePort port = mock(BookResourcePort.class);
        when(port.readBookData(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(0, String.class);
            String content = contentByFile.get(fileName);
            if (content == null) return Optional.empty();
            InputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            return Optional.of(input);
        });
        return port;
    }

    private static final class ImmediateExecutor implements ExecutorPort {
        @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
            try { return CompletableFuture.completedFuture(task.call()); }
            catch (Exception e) { return CompletableFuture.failedFuture(e); }
        }
        @Override public void execute(Runnable task) { task.run(); }
    }

    private static final class ControlledExecutor implements ExecutorPort {
        private Callable<?> submitted;
        private CompletableFuture<Object> future;
        @Override @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> submit(Callable<T> task) {
            submitted = task;
            future = new CompletableFuture<>();
            return (CompletableFuture<T>) (CompletableFuture<?>) future;
        }
        @Override public void execute(Runnable task) { throw new UnsupportedOperationException(); }
        void runSubmitted() throws Exception {
            try { future.complete(submitted.call()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        }
    }

    private static final class InMemoryStore implements ExactDuplicateScanStore {
        private final List<ArtifactScanCandidate> candidates;
        private final boolean resumed;
        private final Map<String, String> hashes = new LinkedHashMap<>();
        private final Map<String, Long> sizes = new LinkedHashMap<>();
        private int cursor;
        private long processed;
        private long hashed;
        private long skipped;
        private long bytes;

        private InMemoryStore(List<ArtifactScanCandidate> candidates, boolean resumed) {
            this.candidates = List.copyOf(candidates);
            this.resumed = resumed;
        }

        @Override public ExactDuplicateScanSession resumeOrStart() { return session(); }
        @Override public List<ArtifactScanCandidate> nextBatch(String scanId, int limit) {
            if (cursor >= candidates.size()) return List.of();
            int end = Math.min(candidates.size(), cursor + limit);
            return candidates.subList(cursor, end);
        }
        @Override public ExactDuplicateScanSession markHashed(String scanId, String artifactId, String sha256, long sizeBytes) {
            ArtifactScanCandidate current = candidates.get(cursor);
            assertThat(current.artifactId()).isEqualTo(artifactId);
            hashes.put(artifactId, sha256);
            sizes.put(artifactId, sizeBytes);
            cursor++;
            processed++;
            hashed++;
            bytes += sizeBytes;
            return session();
        }
        @Override public ExactDuplicateScanSession markSkipped(String scanId, String artifactId, String reason) {
            cursor++;
            processed++;
            skipped++;
            return session();
        }
        @Override public ExactDuplicateScanSession pause(String scanId) { return session(); }
        @Override public ExactDuplicateScanSession complete(String scanId) { return session(); }
        @Override public List<ExactDuplicateGroup> findDuplicateGroups() {
            Map<String, List<ExactDuplicateArtifact>> groups = new LinkedHashMap<>();
            for (ArtifactScanCandidate candidate : candidates) {
                String hash = hashes.get(candidate.artifactId());
                if (hash == null || hash.equals("preexisting-hash")) continue;
                groups.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(new ExactDuplicateArtifact(
                        candidate.bookId(), candidate.artifactId(), candidate.artifactId(), candidate.format(),
                        candidate.file(), sizes.getOrDefault(candidate.artifactId(), 0L)));
            }
            return groups.entrySet().stream().filter(e -> e.getValue().size() > 1)
                    .map(e -> new ExactDuplicateGroup(e.getKey(), e.getValue())).toList();
        }
        private ExactDuplicateScanSession session() {
            return new ExactDuplicateScanSession("scan-test", candidates.size(), processed, hashed, skipped, bytes, resumed);
        }
    }
}
