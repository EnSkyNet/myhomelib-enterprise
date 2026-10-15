package com.myhomelibcorp.organizer.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizerTargetPublisherTest {
    @TempDir Path temp;

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
    void publishesVerifiedStagedBytesToFreshTarget() throws Exception {
        Path staged = Files.writeString(temp.resolve("staged.fb2"), "verified payload", StandardCharsets.UTF_8);
        Path target = temp.resolve("library/Author/Book.fb2");

        new OrganizerTargetPublisher().publish(staged, target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("verified payload");
        assertThat(staged).exists();
        try (var children = Files.list(target.getParent())) {
            assertThat(children.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.contains(".mhl-tmp-"));
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
                .hasMessageContaining("Destination temporary copy hash mismatch");

        assertThat(target).doesNotExist();
        assertThat(Files.readString(staged)).isEqualTo("after");
        try (var children = Files.list(target.getParent())) {
            assertThat(children.toList()).isEmpty();
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
