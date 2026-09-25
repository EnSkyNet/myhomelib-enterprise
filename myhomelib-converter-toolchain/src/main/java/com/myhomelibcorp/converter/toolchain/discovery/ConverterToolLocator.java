package com.myhomelibcorp.converter.toolchain.discovery;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class ConverterToolLocator {
    private final ApplicationSettingsPort settings;

    public ConverterToolLocator(ApplicationSettingsPort settings) {
        this.settings = settings;
    }

    public Optional<Path> locate(String id, String executableBaseName) {
        String explicit = settings.get(settingKey(id), "").trim();
        if (!explicit.isBlank()) {
            Optional<Path> candidate = validateExplicit(Path.of(stripQuotes(explicit)).toAbsolutePath().normalize(), executableBaseName);
            if (candidate.isPresent()) return candidate;
            // Calibre has a dedicated user-visible path field. If the user explicitly configured
            // a path, do not silently ignore a broken value and report another auto-discovered
            // installation as connected; diagnostics and actual selection must agree.
            if ("calibre".equals(id)) return Optional.empty();
        }

        String executable = platformExecutable(executableBaseName);
        List<Path> bundled = bundledToolRoots().stream()
                .flatMap(root -> bundledCandidates(root, id, executable).stream())
                .toList();
        for (Path candidate : bundled) {
            Optional<Path> valid = validate(candidate.toAbsolutePath().normalize());
            if (valid.isPresent()) return valid;
        }

        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String part : path.split(File.pathSeparator)) {
                if (part == null || part.isBlank()) continue;
                try {
                    Optional<Path> valid = validate(Path.of(part).resolve(executable).toAbsolutePath().normalize());
                    if (valid.isPresent()) return valid;
                } catch (RuntimeException ignored) { }
            }
        }

        for (Path candidate : standardCandidates(id, executable)) {
            Optional<Path> valid = validate(candidate);
            if (valid.isPresent()) return valid;
        }
        return Optional.empty();
    }


    private List<Path> bundledToolRoots() {
        Path launch = AppPaths.launchDir().toAbsolutePath().normalize();
        return List.of(
                launch.resolve("tools").normalize(),
                launch.resolve("app/tools").normalize(),
                launch.resolve("../lib/app/tools").normalize(),
                launch.resolve("../app/tools").normalize()
        );
    }

    /**
     * Known layouts of the optional converter compatibility bundle.  The canonical
     * layout remains {@code converters/<id>/<executable>}; versioned/vendor directories
     * are accepted as read-only compatibility aliases so an extracted tools bundle works
     * without requiring users to rename third-party distributions.
     */
    List<Path> bundledCandidates(Path root, String id, String executable) {
        Path converters = root.resolve("converters");
        List<Path> candidates = new ArrayList<>();
        candidates.add(converters.resolve(id).resolve(executable));
        if ("fbc".equals(id)) {
            candidates.add(converters.resolve("fbc-windows-amd64").resolve(executable));
        } else if ("pandoc".equals(id)) {
            candidates.add(converters.resolve("pandoc-3.11").resolve(executable));
        } else if ("calibre".equals(id)) {
            candidates.add(converters.resolve("Calibre").resolve(executable));
            candidates.add(converters.resolve("calibre").resolve(executable));
        }
        return List.copyOf(candidates);
    }

    private List<Path> standardCandidates(String id, String executable) {
        List<Path> candidates = new ArrayList<>();
        if (isWindows()) {
            String pf = System.getenv("ProgramFiles");
            String pfx86 = System.getenv("ProgramFiles(x86)");
            if (pf != null) {
                if ("pandoc".equals(id)) candidates.add(Path.of(pf, "Pandoc", executable));
                if ("calibre".equals(id)) candidates.add(Path.of(pf, "Calibre2", executable));
                candidates.add(Path.of(pf, "MyHomeLib", "tools", "converters", id, executable));
            }
            if (pfx86 != null) {
                if ("pandoc".equals(id)) candidates.add(Path.of(pfx86, "Pandoc", executable));
                if ("calibre".equals(id)) candidates.add(Path.of(pfx86, "Calibre2", executable));
            }
        } else {
            if ("calibre".equals(id) && isMac()) {
                candidates.add(Path.of("/Applications/calibre.app/Contents/MacOS/ebook-convert"));
            }
            candidates.add(Path.of("/usr/local/bin", executable));
            candidates.add(Path.of("/usr/bin", executable));
            Path home = Path.of(System.getProperty("user.home", "."));
            candidates.add(home.resolve(".local/bin").resolve(executable));
        }
        return candidates.stream().map(p -> p.toAbsolutePath().normalize()).toList();
    }


    Optional<Path> validateExplicit(Path path, String executableBaseName) {
        if (path == null || executableBaseName == null || executableBaseName.isBlank()) return Optional.empty();
        Path fileName = path.getFileName();
        if (fileName == null) return Optional.empty();
        String expected = platformExecutable(executableBaseName);
        boolean nameMatches = isWindows()
                ? expected.equalsIgnoreCase(fileName.toString())
                : expected.equals(fileName.toString());
        return nameMatches ? validateCandidate(path) : Optional.empty();
    }

    Optional<Path> validateCandidate(Path path) {
        try {
            Path absolute = ConversionPathSafety.requireSafeRegularFile(
                    path.toAbsolutePath().normalize(), "Converter executable");
            if (!isWindows() && !Files.isExecutable(absolute)) return Optional.empty();
            return Optional.of(absolute);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<Path> validate(Path path) {
        return validateCandidate(path);
    }


    private String settingKey(String id) {
        return "calibre".equals(id)
                ? "converter.calibre.executable"
                : "converter.toolchain." + id + ".executable";
    }

    private String platformExecutable(String base) {
        if (isWindows() && !base.toLowerCase(Locale.ROOT).endsWith(".exe")) return base + ".exe";
        return base;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
