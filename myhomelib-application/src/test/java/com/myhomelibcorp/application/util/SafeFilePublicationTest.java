package com.myhomelibcorp.application.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SafeFilePublicationTest {
    @TempDir Path temp;

    @Test
    void userSelectedConversionPublicationIsCompleteAndLeavesNoStagingFolders() throws Exception {
        Path input = Files.writeString(temp.resolve("source.epub"), "published-book-bytes");
        Path directory = Files.createDirectory(temp.resolve("selected"));
        Path target = directory.resolve("converted.epub");

        var snapshot = SafeFilePublication.publishFreshToUserSelectedPath(input, target, "conversion");

        assertEquals("published-book-bytes", Files.readString(target));
        assertEquals(Files.size(input), snapshot.size());
        assertEquals(SafeFilePublication.sha256(input, "source"), snapshot.sha256());
        assertEquals("published-book-bytes", Files.readString(input));
        try (var entries = Files.list(directory)) {
            assertEquals(java.util.List.of(target), entries.toList());
        }
    }

    @Test
    void userSelectedConversionPublicationAcceptsExplicitSymlinkedParentWhenAvailable() throws Exception {
        Path source = Files.writeString(temp.resolve("source-alias.epub"), "converted");
        Path actual = Files.createDirectory(temp.resolve("real-device-folder"));
        Path alias = temp.resolve("chosen-link");
        try {
            Files.createSymbolicLink(alias, actual.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return;
        }
        Path target = alias.resolve("converted.epub");

        var snapshot = SafeFilePublication.publishFreshToUserSelectedPath(source, target, "conversion");

        assertEquals("converted", Files.readString(actual.resolve("converted.epub")));
        assertEquals(Files.size(source), snapshot.size());
        try (var entries = Files.list(actual)) {
            assertEquals(java.util.List.of(actual.resolve("converted.epub")), entries.toList());
        }
    }

    @Test
    void userSelectedConversionPublicationNeverOverwritesExistingBook() throws Exception {
        Path input = Files.writeString(temp.resolve("source.epub"), "new book");
        Path directory = Files.createDirectory(temp.resolve("selected"));
        Path target = Files.writeString(directory.resolve("converted.epub"), "old book");

        assertThrows(FileAlreadyExistsException.class,
                () -> SafeFilePublication.publishFreshToUserSelectedPath(input, target, "conversion"));

        assertEquals("old book", Files.readString(target));
        try (var entries = Files.list(directory)) {
            assertEquals(java.util.List.of(target), entries.toList());
        }
    }

    @Test
    void concurrentConversionPublishersNeverOverwriteTheWinner() throws Exception {
        Path one = Files.writeString(temp.resolve("first.epub"), "FIRST EPUB");
        Path two = Files.writeString(temp.resolve("second.epub"), "SECOND EPUB");
        Path dir = Files.createDirectory(temp.resolve("concurrent-conversion"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 12; i++) {
                Path target = dir.resolve("book-" + i + ".epub");
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                var first = pool.submit(() -> publishFromGate(one, target, ready, start));
                var second = pool.submit(() -> publishFromGate(two, target, ready, start));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                boolean wonFirst = first.get(10, TimeUnit.SECONDS);
                boolean wonSecond = second.get(10, TimeUnit.SECONDS);
                assertNotEquals(wonFirst, wonSecond, "Exactly one fresh publication may win");
                assertEquals(wonFirst ? "FIRST EPUB" : "SECOND EPUB", Files.readString(target));
            }
        }
        assertEquals("FIRST EPUB", Files.readString(one));
        assertEquals("SECOND EPUB", Files.readString(two));
    }

    private static boolean publishFromGate(Path source, Path target, CountDownLatch ready,
                                          CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Publisher timeout");
        try {
            SafeFilePublication.publishFreshToUserSelectedPath(source, target, "conversion");
            return true;
        } catch (FileAlreadyExistsException alreadyExists) {
            return false;
        }
    }

    @Test
    void moveNoReplaceNeverOverwritesExistingTarget() throws Exception {
        Path source = temp.resolve("source.bin");
        Path target = temp.resolve("target.bin");
        Files.writeString(source, "SOURCE", StandardCharsets.UTF_8);
        Files.writeString(target, "TARGET", StandardCharsets.UTF_8);

        assertThrows(FileAlreadyExistsException.class,
                () -> SafeFilePublication.moveNoReplace(source, target, "test publication"));

        assertEquals("SOURCE", Files.readString(source));
        assertEquals("TARGET", Files.readString(target));
    }

    @Test
    void snapshotCapturesHashAndSizeFromTheSameFileHandle() throws Exception {
        Path target = temp.resolve("snapshot.bin");
        byte[] payload = "fingerprint-payload".getBytes(StandardCharsets.UTF_8);
        Files.write(target, payload);

        SafeFilePublication.FileSnapshot snapshot = SafeFilePublication.snapshot(target, "snapshot");

        assertEquals(payload.length, snapshot.size());
        assertEquals(SafeFilePublication.sha256(target, "snapshot verification"), snapshot.sha256());
    }

    @Test
    void detachIfOwnedRejectsBytesChangedAfterSnapshot() throws Exception {
        Path target = temp.resolve("target.bin");
        Files.writeString(target, "OLD", StandardCharsets.UTF_8);
        String snapshot = SafeFilePublication.sha256(target, "snapshot");
        Files.writeString(target, "CHANGED", StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class,
                () -> SafeFilePublication.detachIfOwned(target, snapshot, "overwrite"));
        assertEquals("CHANGED", Files.readString(target));
    }

    @Test
    void publishReplacingIfUnchangedReplacesOnlySnapshotBytes() throws Exception {
        Path target = temp.resolve("target.bin");
        Path staged = temp.resolve("staged.bin");
        Files.writeString(target, "OLD", StandardCharsets.UTF_8);
        String snapshot = SafeFilePublication.snapshotExistingTarget(target, "target");
        Files.writeString(staged, "NEW", StandardCharsets.UTF_8);

        SafeFilePublication.publishReplacingIfUnchanged(staged, target, snapshot, "replace");

        assertEquals("NEW", Files.readString(target));
        assertFalse(Files.exists(staged));
    }

    @Test
    void publishReplacingIfUnchangedPreservesConcurrentReplacement() throws Exception {
        Path target = temp.resolve("target.bin");
        Path staged = temp.resolve("staged.bin");
        Files.writeString(target, "OLD", StandardCharsets.UTF_8);
        String snapshot = SafeFilePublication.snapshotExistingTarget(target, "target");
        Files.writeString(staged, "NEW", StandardCharsets.UTF_8);
        Files.writeString(target, "FOREIGN", StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class,
                () -> SafeFilePublication.publishReplacingIfUnchanged(staged, target, snapshot, "replace"));
        assertEquals("FOREIGN", Files.readString(target));
        assertEquals("NEW", Files.readString(staged));
    }


    @Test
    void publishReplacingIfUnchangedRejectsStagingBytesChangedAfterValidation() throws Exception {
        Path target = temp.resolve("target.bin");
        Path staged = temp.resolve("staged.bin");
        Files.writeString(staged, "EXPECTED", StandardCharsets.UTF_8);
        String stagedHash = SafeFilePublication.sha256(staged, "staging snapshot");
        Files.writeString(staged, "REPLACED", StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class,
                () -> SafeFilePublication.publishReplacingIfUnchanged(
                        staged, target, null, stagedHash, "fresh publication"));
        assertFalse(Files.exists(target));
        assertEquals("REPLACED", Files.readString(staged));
    }

    @Test
    void freshOutputRefusesDanglingSymlinkWhenSupported() throws Exception {
        Path target = temp.resolve("target.bin");
        Path missing = temp.resolve("missing.bin");
        try {
            Files.createSymbolicLink(target, missing.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return;
        }

        assertThrows(java.io.IOException.class,
                () -> SafeFilePublication.openFreshOutputStream(target, "fresh target"));
        assertTrue(Files.isSymbolicLink(target));
        assertFalse(Files.exists(missing));
    }
    @Test
    void cleanupRefusesWorkspaceReplacedByRegularFile() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectory(workspace);
        Files.delete(workspace);
        Files.writeString(workspace, "FOREIGN", StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class,
                () -> SafeFilePublication.cleanupPrivateWorkspace(workspace));
        assertEquals("FOREIGN", Files.readString(workspace));
    }

    @Test
    void cleanupRefusesSymlinkedParentAndPreservesOutsideTreeWhenSupported() throws Exception {
        Path parent = temp.resolve("parent");
        Path workspace = parent.resolve("workspace");
        Files.createDirectories(workspace);
        Files.delete(workspace);
        Files.delete(parent);

        Path outside = temp.resolve("outside");
        Path outsideWorkspace = outside.resolve("workspace");
        Files.createDirectories(outsideWorkspace);
        Path sentinel = outsideWorkspace.resolve("sentinel.txt");
        Files.writeString(sentinel, "KEEP", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(parent, outside);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return;
        }

        assertThrows(java.io.IOException.class,
                () -> SafeFilePublication.cleanupPrivateWorkspace(workspace));
        assertEquals("KEEP", Files.readString(sentinel));
        assertTrue(Files.isSymbolicLink(parent));
    }

}
