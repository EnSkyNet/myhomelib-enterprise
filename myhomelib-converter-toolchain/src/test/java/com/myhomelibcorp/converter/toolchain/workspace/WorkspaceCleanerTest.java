package com.myhomelibcorp.converter.toolchain.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceCleanerTest {
    @TempDir Path tempDir;

    @Test
    void deletesOrdinaryWorkspaceTree() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace/input"));
        Files.writeString(root.resolve("book.tmp"), "data");
        Path workspace = tempDir.resolve("workspace");

        WorkspaceCleaner.deleteRecursively(workspace);

        assertThat(workspace).doesNotExist();
    }

    @Test
    void deletesLargeNestedWorkspaceAndLeavesUnrelatedFiles() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("large-workspace"));
        Path unrelated = Files.writeString(tempDir.resolve("other.txt"), "untouched");
        for (int directory = 0; directory < 16; directory++) {
            Path nested = Files.createDirectories(workspace.resolve("job-" + directory).resolve("output"));
            for (int file = 0; file < 64; file++) {
                Files.writeString(nested.resolve("page-" + file + ".tmp"), "converted");
            }
        }
        Files.createDirectories(workspace.resolve("empty").resolve("nested"));

        WorkspaceCleaner.deleteRecursively(workspace);

        assertThat(workspace).doesNotExist();
        assertThat(Files.readString(unrelated)).isEqualTo("untouched");
        // A repeated close() should be harmless after successful cleanup.
        WorkspaceCleaner.deleteRecursively(workspace);
    }

    @Test
    void unlinksNestedSymlinkWithoutTraversingOutsideWorkspace() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside-nested"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "external");
        Path workspace = Files.createDirectories(tempDir.resolve("with-link").resolve("output"));
        Path nestedLink = workspace.resolve("linked-directory");
        try {
            Files.createSymbolicLink(nestedLink, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Symbolic links unavailable on this Windows host: " + unavailable);
        }

        WorkspaceCleaner.deleteRecursively(workspace.getParent());

        assertThat(workspace.getParent()).doesNotExist();
        assertThat(Files.readString(sentinel)).isEqualTo("external");
    }

    @Test
    void deletesRootSymlinkWithoutTraversingItWhenPlatformAllowsSymlinks() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "keep");
        Path link = tempDir.resolve("workspace-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }

        WorkspaceCleaner.deleteRecursively(link);

        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.readString(sentinel)).isEqualTo("keep");
    }
}
