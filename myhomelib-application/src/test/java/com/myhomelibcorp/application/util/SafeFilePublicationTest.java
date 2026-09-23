package com.myhomelibcorp.application.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SafeFilePublicationTest {
    @TempDir Path temp;

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
