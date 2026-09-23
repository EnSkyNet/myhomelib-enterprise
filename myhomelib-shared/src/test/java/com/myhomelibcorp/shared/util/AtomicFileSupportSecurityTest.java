package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFileSupportSecurityTest {
    @TempDir Path tempDir;

    @Test
    void writeStringRejectsSymlinkTargetWithoutTouchingExternalFile() throws Exception {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "FOREIGN", StandardCharsets.UTF_8);
        Path link = tempDir.resolve("target.txt");
        Assumptions.assumeTrue(canCreateSymlink(link, outside));

        assertThatThrownBy(() -> AtomicFileSupport.writeStringReplacing(link, "NEW", StandardCharsets.UTF_8))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(outside, StandardCharsets.UTF_8)).isEqualTo("FOREIGN");
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }

    @Test
    void writeStringRejectsSymlinkParentWithoutWritingOutside() throws Exception {
        Path outside = tempDir.resolve("outside-dir");
        Files.createDirectories(outside);
        Path parent = tempDir.resolve("link-dir");
        Assumptions.assumeTrue(canCreateSymlink(parent, outside));

        assertThatThrownBy(() -> AtomicFileSupport.writeStringReplacing(
                parent.resolve("target.txt"), "NEW", StandardCharsets.UTF_8))
                .isInstanceOf(java.io.IOException.class);
        assertThat(outside.resolve("target.txt")).doesNotExist();
    }

    @Test
    void writeStringStillReplacesOrdinaryManagedFile() throws Exception {
        Path target = tempDir.resolve("target.txt");
        Files.writeString(target, "OLD", StandardCharsets.UTF_8);

        AtomicFileSupport.writeStringReplacing(target, "NEW", StandardCharsets.UTF_8);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("NEW");
    }

    private static boolean canCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
