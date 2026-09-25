package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fail-closed resolver for native executables launched without a shell. */
public final class ExecutablePathSafety {
    private ExecutablePathSafety() { }

    /**
     * Resolves an explicit path or a bare command name to a verified regular executable file.
     * PATH lookup never accepts symlink/reparse components.
     */
    public static Path resolve(String command, String role) throws IOException {
        String value = command == null ? "" : command.trim();
        if (value.isBlank()) throw new IOException(role + " is not configured");

        final Path parsed;
        try {
            parsed = Path.of(value);
        } catch (RuntimeException invalid) {
            throw new IOException(role + " has an invalid path: " + value, invalid);
        }

        if (parsed.isAbsolute() || parsed.getParent() != null) {
            return requireExecutable(parsed.toAbsolutePath().normalize(), role);
        }

        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            throw new IOException(role + " was not found because PATH is empty: " + value);
        }
        for (String part : pathValue.split(java.io.File.pathSeparator)) {
            if (part == null || part.isBlank()) continue;
            final Path directory;
            try {
                directory = AtomicFileSupport.requireSafeDirectoryPath(
                        Path.of(part).toAbsolutePath().normalize(), role + " PATH directory");
            } catch (IOException | RuntimeException unsafeDirectory) {
                continue;
            }
            for (String candidateName : candidateNames(value)) {
                Path candidate = directory.resolve(candidateName).normalize();
                try {
                    return requireExecutable(candidate, role);
                } catch (IOException ignored) {
                    // Continue with the next PATH candidate.
                }
            }
        }
        throw new IOException(role + " was not found in safe PATH entries: " + value);
    }

    /** Validates a path that is already known to be the desired executable. */
    public static Path requireExecutable(Path executable, String role) throws IOException {
        Path safe = AtomicFileSupport.requireSafeRegularFile(executable, role);
        if (!isWindows() && !Files.isExecutable(safe)) {
            throw new IOException(role + " is not executable: " + safe);
        }
        return safe;
    }

    /** Returns true when the token is an explicit filesystem path instead of a PATH lookup name. */
    public static boolean isExplicitPath(String command) {
        if (command == null || command.isBlank()) return false;
        try {
            Path path = Path.of(command.trim());
            return path.isAbsolute() || path.getParent() != null;
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    private static List<String> candidateNames(String command) {
        if (!isWindows()) return List.of(command);
        String upper = command.toUpperCase(Locale.ROOT);
        String pathext = System.getenv("PATHEXT");
        List<String> extensions = pathext == null || pathext.isBlank()
                ? List.of(".COM", ".EXE", ".BAT", ".CMD")
                : java.util.Arrays.stream(pathext.split(";"))
                        .map(String::trim).filter(value -> !value.isBlank()).toList();
        for (String extension : extensions) {
            if (upper.endsWith(extension.toUpperCase(Locale.ROOT))) return List.of(command);
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(command);
        for (String extension : extensions) names.add(command + extension.toLowerCase(Locale.ROOT));
        for (String extension : extensions) names.add(command + extension.toUpperCase(Locale.ROOT));
        return List.copyOf(new ArrayList<>(names));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
