package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoLinkPathSafetyTest {
    @TempDir Path tempDir;

    @Test
    void canonicalForCreationPreservesMissingSuffixWithoutCreatingIt() throws Exception {
        Path expected = tempDir.resolve("missing/child/book.fb2").toAbsolutePath().normalize();

        Path actual = NoLinkPathSafety.canonicalForCreation(expected, "target");

        assertThat(actual).isEqualTo(expected);
        assertThat(Files.exists(tempDir.resolve("missing"))).isFalse();
    }

    @Test
    void requireUnderRootRejectsSiblingWithCommonPrefix() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("library"));
        Path sibling = tempDir.resolve("library-copy/book.fb2");

        assertThatThrownBy(() -> NoLinkPathSafety.requireUnderRoot(root, sibling, "book"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("escapes configured root");
    }

    @Test
    void nearestExistingAncestorStopsAtExistingDirectory() throws Exception {
        Path existing = Files.createDirectories(tempDir.resolve("a/b"));

        assertThat(NoLinkPathSafety.nearestExistingAncestor(existing.resolve("c/d")))
                .isEqualTo(existing.toAbsolutePath().normalize());
    }

    @Test
    void freshCopyMustNeverAcceptItsSourceAsDestination() throws Exception {
        Path book = Files.writeString(tempDir.resolve("book.fb2"), "precious content");
        assertThatThrownBy(() -> NoLinkPathSafety.copyFreshFile(book, book, "book copy"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("onto itself");
        assertThat(Files.readString(book)).isEqualTo("precious content");
    }

    @Test
    void freshCopyRefusesExistingDestinationAndPreservesBothFiles() throws Exception {
        Path source = Files.writeString(tempDir.resolve("new.fb2"), "new data");
        Path existing = Files.writeString(tempDir.resolve("old.fb2"), "existing book");
        assertThatThrownBy(() -> NoLinkPathSafety.copyFreshFile(source, existing, "book copy"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(source)).isEqualTo("new data");
        assertThat(Files.readString(existing)).isEqualTo("existing book");
    }

    @Test
    void freshOutputCollisionRetainsFileAlreadyExistsSignal() throws Exception {
        Path target = Files.writeString(tempDir.resolve("published.fb2"), "original");
        assertThatThrownBy(() -> NoLinkPathSafety.openFreshOutputStream(target, "publish"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(target)).isEqualTo("original");
    }

    @Test
    void freshCopyCreatesCompleteIndependentFile() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.fb2"), "original data");
        Path target = tempDir.resolve("target.fb2");
        assertThat(NoLinkPathSafety.copyFreshFile(source, target, "book copy")).isEqualTo(target);
        Files.writeString(source, "changed afterwards");
        assertThat(Files.readString(target)).isEqualTo("original data");
    }

    @Test
    void cancelledCopyRetainsPartialTargetOnlyWithoutFileIdentity() throws Exception {
        Path source = tempDir.resolve("large-source.fb2");
        byte[] chunk = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(source)) {
            for (int i = 0; i < 12; i++) out.write(chunk);
        }
        Path destination = tempDir.resolve("cancelled-target.fb2");
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> NoLinkPathSafety.copyFreshFile(source, destination,
                "large copy", () -> polls.incrementAndGet() >= 5))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(polls.get()).isGreaterThanOrEqualTo(5);
        assertThat(Files.size(source)).isEqualTo(12L * 1024 * 1024);
        if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            var attrs = Files.readAttributes(destination, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            assertThat(attrs.isRegularFile()).isTrue();
            assertThat(attrs.fileKey()).as("Only unprovable ownership permits retained partial bytes").isNull();
            assertThat(attrs.size()).isLessThan(Files.size(source));
        }
    }

    @Test
    void verifiedCopyCancelledAtFirstWrittenChunkDoesNotLeaveOwnedTemporaryData() throws Exception {
        // Must exceed the 1 MiB transfer buffer: a single BYTES milestone
        // may otherwise contain the entire file before cancellation is observed.
        Path source = Files.write(tempDir.resolve("verified-source.fb2"), new byte[2 * 1024 * 1024]);
        Path target = tempDir.resolve("verified-target.fb2");
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> NoLinkPathSafety.copyFreshFileVerified(
                source, target, "verified copy", cancelled::get, digest, (stage, bytes) -> {
                    if ("BYTES".equals(stage) && bytes > 0) cancelled.set(true);
                })).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(source).exists();
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            var attrs = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            assertThat(attrs.fileKey()).as("Only targets with unprovable identity may be retained").isNull();
            assertThat(attrs.size()).isLessThan(Files.size(source));
        }
    }

    @Test
    void cancellationBeforeCopyNeverCreatesDestination() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source-cancel.fb2"), "original");
        Path destination = tempDir.resolve("destination-cancel.fb2");
        assertThatThrownBy(() -> NoLinkPathSafety.copyFreshFile(source, destination,
                "cancelled", () -> true))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(destination).doesNotExist();
        assertThat(Files.readString(source)).isEqualTo("original");
    }

    @Test
    void workspaceCleanupVisitsManyNestedFilesWithoutFollowingSymbolicLinks() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("mhl-private"));
        for (int folder = 0; folder < 20; folder++) {
            Path child = Files.createDirectory(workspace.resolve("part-" + folder));
            for (int file = 0; file < 20; file++) {
                Files.writeString(child.resolve("entry-" + file), "data");
            }
        }
        Path outside = Files.writeString(tempDir.resolve("outside.fb2"), "untouched");
        try {
            Files.createSymbolicLink(workspace.resolve("external-link"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unsupported) {
            // Filesystems without symlink support still exercise streamed cleanup.
        }
        NoLinkPathSafety.cleanupPrivateWorkspace(workspace, "workspace");
        assertThat(workspace).doesNotExist();
        assertThat(Files.readString(outside)).isEqualTo("untouched");
    }
}
