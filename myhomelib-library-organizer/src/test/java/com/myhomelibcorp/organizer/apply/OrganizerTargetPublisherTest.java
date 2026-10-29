package com.myhomelibcorp.organizer.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizerTargetPublisherTest {
    @TempDir Path temp;

    @Test
    void cancellationRetainsReplacedTemporaryFile() throws Exception {
        assertReplacedTemporaryRetained(true);
    }

    @Test
    void hashFailureRetainsReplacedTemporaryFile() throws Exception {
        assertReplacedTemporaryRetained(false);
    }

    private void assertReplacedTemporaryRetained(boolean cancel) throws Exception {
        Path staged = Files.writeString(temp.resolve("source.fb2"), "owned source");
        Path directory = Files.createDirectory(temp.resolve("destination"));
        Path target = directory.resolve("book.fb2");
        var replaced = new java.util.concurrent.atomic.AtomicReference<Path>();
        String digest = OrganizerFileIntegritySupport.sha256(staged);
        java.util.function.BooleanSupplier race = () -> {
            if (replaced.get() != null) return cancel;
            try (var files = Files.list(directory)) {
                Path candidate = files.filter(p -> p.getFileName().toString().startsWith(".mhl-tmp-"))
                        .findFirst().orElse(null);
                if (candidate != null && Files.size(candidate) == Files.size(staged)) {
                    Files.move(candidate, directory.resolve("detached-owned-copy"));
                    Files.writeString(candidate, "foreign replacement", java.nio.file.StandardOpenOption.CREATE_NEW);
                    replaced.set(candidate);
                    return cancel;
                }
                return false;
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        };
        assertThatThrownBy(() -> new OrganizerTargetPublisher().publish(staged, target, digest, race,
                (stage, bytes) -> { }, false))
                .isInstanceOf(cancel ? java.util.concurrent.CancellationException.class : IllegalStateException.class);
        assertThat(replaced.get()).as("The replacement race must actually execute").isNotNull();
        assertThat(replaced.get()).exists();
        assertThat(Files.readString(replaced.get())).isEqualTo("foreign replacement");
        assertThat(Files.readString(staged)).isEqualTo("owned source");
        assertThat(target).doesNotExist();
    }

    @Test
    void cancelledPublicationRetainsUnprovenPartialOnWindowsButNeverDeletesSource() throws Exception {
        Path staged = temp.resolve("large-stage.fb2");
        byte[] block = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(staged)) {
            for (int i = 0; i < 12; i++) out.write(block);
        }
        Path target = temp.resolve("destination/book.fb2");
        String digest = OrganizerFileIntegritySupport.sha256(staged);
        var polls = new java.util.concurrent.atomic.AtomicInteger();

        assertThatThrownBy(() -> new OrganizerTargetPublisher().publish(
                staged, target, digest, () -> polls.incrementAndGet() >= 5))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(polls.get()).isGreaterThanOrEqualTo(5);
        // A Windows CREATE_NEW fallback may leave an unproven partial target,
        // because deleting it without fileKey could erase a competing file.
        assertCleanedOrRetainedWithoutFileIdentity(target);
        assertThat(staged).exists();
        try (var stagedFiles = Files.list(target.getParent())) {
            for (Path path : stagedFiles.toList()) assertCleanedOrRetainedWithoutFileIdentity(path);
        }
    }

    @Test
    void longValidNamesDoNotOverflowTemporaryNames() throws Exception {
        Path source = Files.writeString(temp.resolve("a".repeat(230) + ".fb2"), "long name payload");
        Path target = temp.resolve("target").resolve("b".repeat(230) + ".fb2");
        String hash = OrganizerFileIntegritySupport.sha256(source);
        new OrganizerTargetPublisher().publish(source, target, hash);
        assertThat(Files.readString(target)).isEqualTo("long name payload");
        OrganizerFileIntegritySupport.removeOwnedFile(source, hash, "test owned source");
        assertThat(source).doesNotExist();
        assertThat(target).exists();
    }

    @Test
    void streamingHashObservesCancellationBetweenBoundedReads() throws Exception {
        Path source = Files.write(temp.resolve("hash-cancel.fb2"), new byte[512 * 1024]);
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> OrganizerFileIntegritySupport.sha256(source, () -> polls.incrementAndGet() >= 3))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(Files.size(source)).isEqualTo(512 * 1024);
    }

    @Test
    void sourceChangedAtCopyStartNeverPublishesUnverifiedStagingFile() throws Exception {
        Path source = Files.writeString(temp.resolve("racing-source.fb2"), "original text", StandardCharsets.UTF_8);
        String initialDigest = OrganizerFileIntegritySupport.sha256(source);
        Path staged = temp.resolve("staging/unverified.fb2");
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.BooleanSupplier concurrentEdit = () -> {
            boolean copyHasStarted = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals("com.myhomelibcorp.shared.util.NoLinkPathSafety")
                            && frame.getMethodName().startsWith("copyFreshFile")));
            if (copyHasStarted && changed.compareAndSet(false, true)) {
                try {
                    Files.writeString(source, "concurrent replacement", StandardCharsets.UTF_8);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }
            return false;
        };

        assertThatThrownBy(() -> OrganizerFileIntegritySupport.copyAndVerify(
                source, staged, initialDigest, concurrentEdit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash mismatch");
        assertThat(changed.get()).isTrue();
        assertCleanedOrRetainedWithoutFileIdentity(staged);
        assertThat(Files.readString(source)).isEqualTo("concurrent replacement");
    }

    @Test
    void cancellationAfterCopyBeforeTargetHashCleansOnlyProvablyOwnedStagingBytes() throws Exception {
        Path source = Files.writeString(temp.resolve("complete-source.fb2"), "complete payload");
        Path staged = temp.resolve("staging/finished-but-cancelled.fb2");
        String hash = OrganizerFileIntegritySupport.sha256(source);
        var checkpoints = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BooleanSupplier cancelAfterCopy = () ->
                // With a small source the seventh cancellation check falls after
                // streaming: in V20's post-copy check, or V21's target re-read.
                checkpoints.incrementAndGet() >= 7;

        assertThatThrownBy(() -> OrganizerFileIntegritySupport.copyAndVerify(
                source, staged, hash, cancelAfterCopy))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(Files.readString(source)).isEqualTo("complete payload");
        assertCleanedOrRetainedWithoutFileIdentity(staged);
    }

    @Test
    void verifiedCopyEmitsFirstChunkAndVerificationMilestones() throws Exception {
        Path source = Files.write(temp.resolve("progress-source.fb2"), new byte[2 * 1024 * 1024 + 5]);
        Path staged = temp.resolve("staging/progress.fb2");
        String digest = OrganizerFileIntegritySupport.sha256(source);
        java.util.List<String> stages = new java.util.ArrayList<>();
        java.util.List<Long> copied = new java.util.ArrayList<>();

        OrganizerFileIntegritySupport.copyAndVerify(source, staged, digest, () -> false, (stage, bytes) -> {
            stages.add(stage);
            copied.add(bytes);
        });

        assertThat(stages).containsSubsequence("OPENED", "BYTES", "STREAM_DONE",
                "VERIFY_TARGET_START", "VERIFY_TARGET_DONE");
        assertThat(copied.get(stages.indexOf("BYTES"))).isPositive();
        assertThat(copied.get(stages.indexOf("STREAM_DONE"))).isEqualTo(Files.size(source));
        assertThat(copied.get(stages.indexOf("VERIFY_TARGET_DONE"))).isEqualTo(Files.size(source));
        assertThat(OrganizerFileIntegritySupport.sha256(staged)).isEqualTo(digest);
    }

    @Test
    void cancellationAtDestinationVerificationMilestoneCleansOnlyProvablyOwnedOutput() throws Exception {
        Path source = Files.writeString(temp.resolve("source.fb2"), "milestone cancel");
        Path staged = temp.resolve("staging/milestone-cancel.fb2");
        String digest = OrganizerFileIntegritySupport.sha256(source);
        var cancel = new java.util.concurrent.atomic.AtomicBoolean();

        assertThatThrownBy(() -> OrganizerFileIntegritySupport.copyAndVerify(source, staged, digest,
                cancel::get, (stage, bytes) -> {
                    if ("VERIFY_TARGET_START".equals(stage)) cancel.set(true);
                })).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertCleanedOrRetainedWithoutFileIdentity(staged);
        assertThat(Files.readString(source)).isEqualTo("milestone cancel");
    }

    @Test
    void publicationReportsCopyBytesAndVerificationStagesWithoutChangingPayload() throws Exception {
        byte[] payload = new byte[512 * 1024 + 7];
        java.util.Arrays.fill(payload, (byte) 42);
        Path staged = Files.write(temp.resolve("progress-source.fb2"), payload);
        Path target = temp.resolve("progress/target.fb2");
        String digest = OrganizerFileIntegritySupport.sha256(staged);
        java.util.List<String> stages = new java.util.ArrayList<>();
        java.util.List<Long> copied = new java.util.ArrayList<>();
        new OrganizerTargetPublisher().publish(staged, target, digest, () -> false,
                (stage, bytes) -> { stages.add(stage); copied.add(bytes); }, false);
        assertThat(stages).containsSubsequence("TEMP_OPENED", "TEMP_BYTES", "TEMP_STREAM_DONE",
                "TEMP_VERIFY_TARGET_START", "TEMP_VERIFY_TARGET_DONE", "FINAL_PUBLISH_START",
                "FINAL_VERIFY_START", "FINAL_VERIFIED");
        assertThat(copied.get(stages.lastIndexOf("TEMP_BYTES"))).isEqualTo(payload.length);
        assertThat(stages).contains("FINAL_VERIFY_BYTES");
        assertThat(copied.get(stages.lastIndexOf("FINAL_VERIFY_BYTES"))).isEqualTo(payload.length);
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    @Test
    void cancellationDuringFinalHashDoesNotLeavePublishedTarget() throws Exception {
        byte[] payload = new byte[512 * 1024];
        Path staged = Files.write(temp.resolve("cancel-final-hash.fb2"), payload);
        Path target = temp.resolve("destination/cancel-final-hash.fb2");
        String digest = OrganizerFileIntegritySupport.sha256(staged);
        var cancel = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> new OrganizerTargetPublisher().publish(
                staged, target, digest, cancel::get, (stage, bytes) -> {
                    if ("FINAL_VERIFY_BYTES".equals(stage) && bytes > 0L) cancel.set(true);
                })).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(staged).exists();
        assertCleanedOrRetainedWithoutFileIdentity(target);
    }

    @Test
    void cancellationDuringPublicationVerificationPreservesStagedBytes() throws Exception {
        Path staged = Files.writeString(temp.resolve("cancel-publish.fb2"), "retain source");
        Path target = temp.resolve("cancel/target.fb2");
        String digest = OrganizerFileIntegritySupport.sha256(staged);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> new OrganizerTargetPublisher().publish(staged, target, digest,
                cancelled::get, (stage, bytes) -> {
                    if ("TEMP_VERIFY_TARGET_START".equals(stage)) cancelled.set(true);
                }, false)).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(Files.readString(staged)).isEqualTo("retain source");
        assertThat(target).doesNotExist();
    }

    @Test
    void directWindowsStylePublicationKeepsBothDistinctBooksWithoutOrphanTemporaries() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("wheel-of-time"));
        Path first = Files.writeString(temp.resolve("first.fb2"), "Volume 1-9");
        Path second = Files.writeString(temp.resolve("second.fb2"), "Volume 10-19");
        OrganizerTargetPublisher publisher = new OrganizerTargetPublisher();
        publisher.publish(first, directory.resolve("Колесо Времени 1-9.fb2"),
                OrganizerFileIntegritySupport.sha256(first), () -> false, (stage, bytes) -> { }, true);
        publisher.publish(second, directory.resolve("Колесо Времени 10-19.fb2"),
                OrganizerFileIntegritySupport.sha256(second), () -> false, (stage, bytes) -> { }, true);
        assertThat(Files.readString(directory.resolve("Колесо Времени 1-9.fb2")))
                .isEqualTo("Volume 1-9");
        assertThat(Files.readString(directory.resolve("Колесо Времени 10-19.fb2")))
                .isEqualTo("Volume 10-19");
        try (var children = Files.list(directory)) {
            assertThat(children.map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("Колесо Времени 1-9.fb2", "Колесо Времени 10-19.fb2");
        }
    }

    @Test
    void publishesVerifiedStagedBytesToFreshTarget() throws Exception {
        Path staged = Files.writeString(temp.resolve("staged.fb2"), "verified payload", StandardCharsets.UTF_8);
        Path target = temp.resolve("library/Author/Book.fb2");

        new OrganizerTargetPublisher().publish(staged, target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("verified payload");
        assertThat(staged).exists();
        try (var children = Files.list(target.getParent())) {
            for (Path path : children.filter(p -> !p.equals(target)).toList()) {
                assertCleanedOrRetainedWithoutFileIdentity(path);
                assertThat(Files.readString(path)).isEqualTo("verified payload");
            }
        }
    }

    @Test
    void publishesUsingPreviouslyValidatedDigestWithoutWeakeningDestinationVerification() throws Exception {
        Path staged = Files.writeString(temp.resolve("prevalidated.fb2"), "stable staged bytes", StandardCharsets.UTF_8);
        String digest = OrganizerFileIntegritySupport.sha256(staged);
        Path target = temp.resolve("prepared/Book.fb2");

        new OrganizerTargetPublisher().publish(staged, target, digest);

        assertThat(OrganizerFileIntegritySupport.sha256(target)).isEqualTo(digest);
        assertThat(Files.readString(staged)).isEqualTo("stable staged bytes");
    }

    @Test
    void rejectsChangedStagingContentWhenProvidedDigestIsStale() throws Exception {
        Path staged = Files.writeString(temp.resolve("changing.fb2"), "before", StandardCharsets.UTF_8);
        String previouslyValidated = OrganizerFileIntegritySupport.sha256(staged);
        Files.writeString(staged, "after", StandardCharsets.UTF_8);
        Path target = temp.resolve("library/Changed.fb2");

        assertThatThrownBy(() -> new OrganizerTargetPublisher().publish(staged, target, previouslyValidated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Source hash mismatch");

        assertThat(target).doesNotExist();
        assertThat(Files.readString(staged)).isEqualTo("after");
        try (var children = Files.list(target.getParent())) {
            for (Path path : children.toList()) assertCleanedOrRetainedWithoutFileIdentity(path);
        }
    }

    @Test
    void rejectsUnknownDigestBeforeCreatingTarget() throws Exception {
        Path staged = Files.writeString(temp.resolve("unknown.fb2"), "data", StandardCharsets.UTF_8);
        Path target = temp.resolve("library/Book.fb2");
        assertThatThrownBy(() -> new OrganizerTargetPublisher().publish(staged, target, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(target.getParent()).doesNotExist();
    }

    @Test
    void competingPublishersCannotOverwriteWinningBook() throws Exception {
        Path left = Files.writeString(temp.resolve("left.fb2"), "LEFT PAYLOAD");
        Path right = Files.writeString(temp.resolve("right.fb2"), "RIGHT PAYLOAD");
        Path directory = Files.createDirectories(temp.resolve("competition"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 12; round++) {
                Path target = directory.resolve("book-" + round + ".fb2");
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                var first = pool.submit(() -> tryPublish(left, target, ready, go));
                var second = pool.submit(() -> tryPublish(right, target, ready, go));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                boolean wonLeft = first.get(10, TimeUnit.SECONDS);
                boolean wonRight = second.get(10, TimeUnit.SECONDS);
                assertThat(wonLeft).isNotEqualTo(wonRight);
                assertThat(Files.readString(target)).isEqualTo(wonLeft ? "LEFT PAYLOAD" : "RIGHT PAYLOAD");
            }
        }
        assertThat(Files.readString(left)).isEqualTo("LEFT PAYLOAD");
        assertThat(Files.readString(right)).isEqualTo("RIGHT PAYLOAD");
    }

    /**
     * Windows' default NIO provider may return null for BasicFileAttributes.fileKey().
     * A failed copy cannot prove that a path still names its own CREATE_NEW artifact
     * when that happens, so it must retain the artifact rather than delete a
     * potentially replaced file. Unix providers with an identity can clean it.
     */
    private static void assertCleanedOrRetainedWithoutFileIdentity(Path staged) throws Exception {
        if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attrs = Files.readAttributes(staged, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(attrs.isRegularFile()).isTrue();
        assertThat(attrs.fileKey())
                .as("A failed copy may retain its artifact only when the provider cannot prove ownership")
                .isNull();
    }

    private static boolean tryPublish(Path staged, Path target, CountDownLatch ready,
                                      CountDownLatch go) throws Exception {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timeout");
        try {
            new OrganizerTargetPublisher().publish(staged, target);
            return true;
        } catch (FileAlreadyExistsException expected) {
            return false;
        }
    }

    @Test
    void refusesExistingTargetWithoutChangingEitherFile() throws Exception {
        Path staged = Files.writeString(temp.resolve("staged.fb2"), "staged", StandardCharsets.UTF_8);
        Path target = temp.resolve("existing.fb2");
        Files.writeString(target, "foreign", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new OrganizerTargetPublisher().publish(staged, target))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(staged, StandardCharsets.UTF_8)).isEqualTo("staged");
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("foreign");
    }
}
