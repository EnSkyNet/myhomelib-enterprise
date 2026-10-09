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
}
