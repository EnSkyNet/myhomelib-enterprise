package com.myhomelibcorp.infrastructure.epub;

import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Single source of truth for EPUBCheck discovery and command construction. */
@Component
final class EpubCheckToolCommandFactory {
    ResolvedTool resolve(String explicit) {
        String cleaned = clean(explicit);
        if (!cleaned.isBlank()) {
            try {
                Path path = Path.of(cleaned).toAbsolutePath().normalize();
                return fromPath(path, true);
            } catch (RuntimeException invalidPath) {
                return ResolvedTool.unavailable(cleaned, "Invalid EPUBCheck path");
            }
        }

        // 1) Bundled locations (validators/epubcheck/epubcheck.jar, epubcheck/epubcheck.jar)
        for (Path path : bundledCandidates()) {
            if (isSafeRegularFile(path)) return fromPath(path, false);
        }

        // 2) PATH lookup — prefer epubcheck.jar (launched via `java -jar`), then the
        //    platform launcher scripts/binaries. JAR is preferred because it does not
        //    depend on a working .bat/.cmd/.exe wrapper and behaves the same on all OSes.
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String name : pathCandidateNames()) {
                for (String part : pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (part == null || part.isBlank()) continue;
                    try {
                        Path candidate = Path.of(clean(part)).resolve(name).toAbsolutePath().normalize();
                        if (!isSafeRegularFile(candidate)) continue;
                        if (name.endsWith(".jar")) {
                            return fromPath(candidate, false);
                        }
                        if (isWindows() || Files.isExecutable(candidate)) {
                            return fromPath(candidate, false);
                        }
                    } catch (RuntimeException ignored) { }
                }
            }
        }

        String diagnosticName = isWindows() ? "epubcheck.jar/epubcheck.bat/epubcheck.exe" : "epubcheck.jar/epubcheck";
        return ResolvedTool.unavailable(diagnosticName,
                "EPUBCheck was not found in configured, bundled, or PATH locations");
    }

    List<String> validationCommand(String explicit, Path epub) {
        ResolvedTool tool = resolve(explicit);
        if (!tool.available()) return List.of();
        ArrayList<String> command = new ArrayList<>(tool.prefix());
        command.add(epub.toAbsolutePath().normalize().toString());
        return List.copyOf(command);
    }

    List<String> versionCommand(String explicit) {
        ResolvedTool tool = resolve(explicit);
        if (!tool.available()) return List.of();
        ArrayList<String> command = new ArrayList<>(tool.prefix());
        command.add("--version");
        return List.copyOf(command);
    }

    private ResolvedTool fromPath(Path path, boolean explicit) {
        if (!isSafeRegularFile(path)) {
            return ResolvedTool.unavailable(path.toString(), explicit
                    ? "Configured EPUBCheck executable or JAR is missing or unsafe"
                    : "EPUBCheck file is missing or unsafe");
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (explicit && !((lower.endsWith(".jar") && lower.contains("epubcheck")) || lower.equals("epubcheck")
                || lower.equals("epubcheck.exe") || lower.equals("epubcheck.bat") || lower.equals("epubcheck.cmd"))) {
            return ResolvedTool.unavailable(path.toString(), "Configured file does not look like EPUBCheck");
        }
        if (lower.endsWith(".jar")) {
            return new ResolvedTool(path.toString(), List.of(javaExecutable(), "-jar", path.toString()), true, "JAR");
        }
        return new ResolvedTool(path.toString(), List.of(path.toString()), true, "EXECUTABLE");
    }

    /**
     * Ordered list of filenames to try when scanning PATH.
     * JAR first (portable, no launcher script needed), then platform-specific launchers.
     */
    private List<String> pathCandidateNames() {
        if (isWindows()) {
            return List.of(
                    "epubcheck.jar",
                    "epubcheck.bat",
                    "epubcheck.cmd",
                    "epubcheck.exe",
                    "epubcheck"
            );
        }
        return List.of(
                "epubcheck.jar",
                "epubcheck"
        );
    }

    private boolean isSafeRegularFile(Path path) {
        if (path == null) return false;
        try {
            ConversionPathSafety.requireSafeRegularFile(path.toAbsolutePath().normalize(), "EPUBCheck tool");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<Path> bundledCandidates() {
        Path launch = AppPaths.launchDir().toAbsolutePath().normalize();
        List<Path> roots = List.of(
                launch.resolve("tools").normalize(),
                launch.resolve("app/tools").normalize(),
                launch.resolve("../lib/app/tools").normalize(),
                launch.resolve("../app/tools").normalize()
        );
        ArrayList<Path> candidates = new ArrayList<>();
        for (Path root : roots) {
            candidates.add(root.resolve("validators/epubcheck/epubcheck.jar"));
            candidates.add(root.resolve("epubcheck/epubcheck.jar"));
        }
        return List.copyOf(candidates);
    }

    private String javaExecutable() {
        String suffix = isWindows() ? "java.exe" : "java";
        Path bundled = Path.of(System.getProperty("java.home", ""), "bin", suffix).toAbsolutePath().normalize();
        return Files.isRegularFile(bundled, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? bundled.toString() : suffix;
    }

    private String clean(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    record ResolvedTool(String displayPath, List<String> prefix, boolean available, String source) {
        ResolvedTool {
            displayPath = displayPath == null ? "" : displayPath;
            prefix = List.copyOf(prefix == null ? List.of() : prefix);
            source = source == null ? "" : source;
        }
        static ResolvedTool unavailable(String path, String diagnostic) {
            return new ResolvedTool(path, List.of(), false, diagnostic == null ? "Unavailable" : diagnostic);
        }
    }
}