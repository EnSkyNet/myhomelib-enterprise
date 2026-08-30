package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Cross-platform atomic-replace helper with a safe fallback for filesystems without ATOMIC_MOVE. */
public final class AtomicFileSupport {
    private AtomicFileSupport() { }

    public static Path moveReplacing(Path source, Path target) throws IOException {
        try {
            return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
