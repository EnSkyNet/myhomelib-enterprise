package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.Utf16BoundarySupport;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a privacy-aware diagnostic ZIP for support requests. */
@Component
public class SupportBundleService {
    static final long MAX_LOG_FILE = 10L * 1024 * 1024;
    static final long MAX_TOTAL_LOGS = 30L * 1024 * 1024;
    // A broken log line may span the entire 10 MiB file. Never retain it as a String.
    static final int MAX_DIAGNOSTIC_LINE_CHARS = 16 * 1024;
    // Prevent a corrupt/overgrown logs directory from consuming unbounded heap/UI space.
    static final int MAX_LOG_ENTRIES = 128;
    private static final String OMITTED_DIAGNOSTIC_LINE = "[oversized diagnostic line omitted]";
    private static final long MAX_RELEASE_FILE = 2L * 1024 * 1024;
    private static final Set<String> SECRET_WORDS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key", "credential", "auth", "encryption.key");
    private static final List<String> RELEASE_FILES = List.of(
            "docs/release/CURRENT-VALIDATION.md", "docs/current/RELEASE.md", "ARCHITECTURE.md", "docs/current/OPERATIONS.md");

    private final ApplicationSettingsPort settings;

    public SupportBundleService(ApplicationSettingsPort settings) {
        this.settings = settings;
    }

    public Path create(Path output) throws IOException {
        return create(output, SupportBundleOptions.defaults());
    }

    public Path create(Path output, SupportBundleOptions options) throws IOException {
        if (output == null) throw new IllegalArgumentException("Output path is required");
        SupportBundleOptions actual = options == null ? SupportBundleOptions.defaults() : options;
        SupportBundleSanitizer sanitizer = new SupportBundleSanitizer();
        Path target = output.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Support bundle target has no parent: " + target);
        String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(target, "support bundle target");
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                parent, ".support-bundle-work-", "support bundle workspace");
        Path tmp = workspace.resolve("support-bundle.zip");
        try {
            try (OutputStream raw = SafeFilePublication.openFreshOutputStream(tmp, "staged support bundle");
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw), StandardCharsets.UTF_8)) {
                putText(zip, "environment.txt", sanitizer.sanitize(environment()));
                putText(zip, "settings-redacted.txt", sanitizer.sanitize(redactedSettings(sanitizer)));
                if (actual.includeThreadDump()) putText(zip, "threads.txt", sanitizer.sanitize(threadDump()));
                if (actual.includeReleaseDocuments()) addValidationFiles(zip, sanitizer);
                if (actual.includeLogs()) addLogs(zip, sanitizer);
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    tmp, target, expectedTargetHash, "support bundle publication");
            return target;
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("Cannot create support bundle", e);
        } finally {
            try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
            catch (Exception ignored) { }
        }
    }

    /** Returns only sanitized names/sizes; file contents are not copied into the preview. */
    public SupportBundlePreview preview(SupportBundleOptions options) {
        SupportBundleOptions actual = options == null ? SupportBundleOptions.defaults() : options;
        List<SupportBundlePreview.Item> items = new ArrayList<>();
        items.add(new SupportBundlePreview.Item("environment.txt", -1, true, UiText.text("ui.java.support_bundle_service.e0851c8d03")));
        items.add(new SupportBundlePreview.Item("settings-redacted.txt", -1, true, UiText.text("ui.java.support_bundle_service.04a4fbb9c8")));
        items.add(new SupportBundlePreview.Item("threads.txt", -1, actual.includeThreadDump(), UiText.text("ui.java.support_bundle_service.967412f09e")));

        for (String name : RELEASE_FILES) {
            Path file = AppPaths.launchDir().resolve(name);
            long size = safeSize(file);
            boolean eligible = isSafeRegularFile(file) && size >= 0 && size <= MAX_RELEASE_FILE;
            items.add(new SupportBundlePreview.Item(releaseEntryName(name), size,
                    actual.includeReleaseDocuments() && eligible,
                    eligible ? UiText.text("ui.java.support_bundle_service.b4244ed067") : UiText.text("ui.java.support_bundle_service.e6ddd515c2")));
        }

        long accepted = 0;
        for (Path file : logFiles()) {
            long size = safeSize(file);
            boolean individuallyEligible = size > 0 && size <= MAX_LOG_FILE;
            boolean withinTotal = individuallyEligible && accepted + size <= MAX_TOTAL_LOGS;
            boolean include = actual.includeLogs() && withinTotal;
            String note;
            if (!actual.includeLogs()) note = UiText.text("ui.java.support_bundle_service.a2fc884d45");
            else if (!individuallyEligible) note = UiText.text("ui.java.support_bundle_service.654608d1ff");
            else if (!withinTotal) note = UiText.text("ui.java.support_bundle_service.cada0354bf");
            else {
                accepted += size;
                note = UiText.text("ui.support_bundle.line_by_line_redaction");
            }
            items.add(new SupportBundlePreview.Item("logs/" + file.getFileName(), size, include, note));
        }
        return new SupportBundlePreview(items);
    }

    private String environment() {
        StringBuilder out = new StringBuilder();
        line(out, "created", Instant.now().toString());
        line(out, "app", "MyHomeLib");
        line(out, "app.version", runtimeVersion());
        line(out, "java.version", System.getProperty("java.version", ""));
        line(out, "java.vendor", System.getProperty("java.vendor", ""));
        line(out, "os.name", System.getProperty("os.name", ""));
        line(out, "os.version", System.getProperty("os.version", ""));
        line(out, "os.arch", System.getProperty("os.arch", ""));
        line(out, "locale", Locale.getDefault().toLanguageTag());
        line(out, "portable", Boolean.toString(AppPaths.portableMode()));
        line(out, "dataDir", "<DATA_DIR>");
        line(out, "launchDir", "<LAUNCH_DIR>");
        line(out, "processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        line(out, "maxMemory", Long.toString(Runtime.getRuntime().maxMemory()));
        return out.toString();
    }

    /** Runtime/build version used by About, diagnostics and support bundles. */
    public static String runtimeVersion() {
        String packaged = trim(System.getProperty("jpackage.app-version"));
        if (!packaged.isBlank()) return packaged;
        Package pkg = SupportBundleService.class.getPackage();
        if (pkg != null) {
            String implementation = trim(pkg.getImplementationVersion());
            if (!implementation.isBlank()) return implementation;
            String specification = trim(pkg.getSpecificationVersion());
            if (!specification.isBlank()) return specification;
        }
        try (InputStream in = SupportBundleService.class.getClassLoader().getResourceAsStream(
                "META-INF/maven/com.myhomelibcorp/myhomelib-ui/pom.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String mavenVersion = trim(properties.getProperty("version"));
                if (!mavenVersion.isBlank()) return mavenVersion;
            }
        } catch (IOException ignored) { }
        return "development";
    }

    private String redactedSettings(SupportBundleSanitizer sanitizer) {
        StringBuilder out = new StringBuilder("# MyHomeLib settings (secrets and private values redacted)\n");
        settings.findByPrefix("").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = entry.getKey();
            String value = isSecret(key) ? "<REDACTED>" : safeSettingValue(entry.getValue(), sanitizer);
            line(out, key, value);
        });
        return out.toString();
    }

    private String safeSettingValue(String value, SupportBundleSanitizer sanitizer) {
        if (value == null || value.isBlank()) return "";
        try {
            if (Path.of(value).isAbsolute()) return "<PATH_REDACTED>";
        } catch (RuntimeException ignored) { }
        return safeValue(sanitizer.sanitizeLine(value));
    }

    private String safeValue(String value) {
        if (value == null) return "";
        if (value.length() <= 4096) return value;
        int end = Utf16BoundarySupport.safeCut(value, 4096);
        return value.substring(0, end) + "…<truncated>";
    }

    private boolean isSecret(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return SECRET_WORDS.stream().anyMatch(lower::contains);
    }

    private String threadDump() {
        StringBuilder out = new StringBuilder();
        Thread.getAllStackTraces().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getName()))
                .forEach(entry -> {
                    Thread t = entry.getKey();
                    out.append('"').append(t.getName()).append("\" id=").append(t.threadId())
                            .append(" state=").append(t.getState()).append('\n');
                    for (StackTraceElement frame : entry.getValue()) out.append("    at ").append(frame).append('\n');
                    out.append('\n');
                });
        return out.toString();
    }

    private void addValidationFiles(ZipOutputStream zip, SupportBundleSanitizer sanitizer) throws IOException {
        for (String name : RELEASE_FILES) {
            Path file = AppPaths.launchDir().resolve(name);
            long size = safeSize(file);
            if (isSafeRegularFile(file) && size >= 0 && size <= MAX_RELEASE_FILE) {
                putSanitizedFile(zip, releaseEntryName(name), file, size, sanitizer, MAX_RELEASE_FILE);
            }
        }
    }


    private static String releaseEntryName(String sourcePath) {
        return "release/" + Path.of(sourcePath).getFileName();
    }

    private void addLogs(ZipOutputStream zip, SupportBundleSanitizer sanitizer) throws IOException {
        long total = 0;
        for (Path file : logFiles()) {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_LOG_FILE || total + size > MAX_TOTAL_LOGS) continue;
            putSanitizedFile(zip, "logs/" + file.getFileName(), file, size, sanitizer, MAX_LOG_FILE);
            total += size;
        }
    }

    private List<Path> logFiles() {
        Path dir = AppPaths.logsDir();
        try {
            SafeFilePublication.createDirectoriesNoLinks(dir, "support bundle log directory");
        } catch (IOException unsafe) {
            return List.of();
        }
        return newestLogFiles(dir);
    }

    /** Select the 128 newest safe regular files with O(128) retained paths, not O(directory size). */
    List<Path> newestLogFiles(Path dir) {
        record LogFile(Path path, long modifiedAt) { }
        Comparator<LogFile> newestFirst = Comparator.comparingLong(LogFile::modifiedAt)
                .reversed().thenComparing(entry -> entry.path().getFileName().toString());
        // The least desirable selected entry is at the head of the bounded heap.
        PriorityQueue<LogFile> selected = new PriorityQueue<>(MAX_LOG_ENTRIES, newestFirst.reversed());
        try (var stream = Files.list(dir)) {
            var entries = stream.iterator();
            while (entries.hasNext()) {
                Path path = entries.next();
                if (!isSafeRegularFile(path)) continue;
                LogFile candidate = new LogFile(path, lastModified(path));
                if (selected.size() < MAX_LOG_ENTRIES) {
                    selected.add(candidate);
                } else if (newestFirst.compare(candidate, selected.peek()) < 0) {
                    selected.poll();
                    selected.add(candidate);
                }
            }
        } catch (IOException | java.io.UncheckedIOException ignored) {
            return List.of();
        }
        List<LogFile> ordered = new ArrayList<>(selected);
        ordered.sort(newestFirst);
        return ordered.stream().map(LogFile::path).toList();
    }

    private long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    private long safeSize(Path path) {
        try { return isSafeRegularFile(path) ? Files.size(path) : -1; }
        catch (IOException e) { return -1; }
    }

    private boolean isSafeRegularFile(Path path) {
        try {
            SafeFilePublication.requireSafeRegularFile(path, "support bundle input");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void putText(ZipOutputStream zip, String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void putSanitizedFile(ZipOutputStream zip,
                                  String name,
                                  Path file,
                                  long expectedSize,
                                  SupportBundleSanitizer sanitizer,
                                  long maxSourceBytes) throws IOException {
        if (expectedSize < 0 || expectedSize > maxSourceBytes) throw new IOException("Diagnostic input exceeds limit: " + file);
        Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "support bundle input");
        ZipEntry entry = new ZipEntry(name.replace('\\', '/'));
        zip.putNextEntry(entry);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(safeFile, LinkOption.NOFOLLOW_LINKS), decoder))) {
            String line;
            while ((line = readBoundedDiagnosticLine(reader)) != null) {
                byte[] sanitized = (sanitizer.sanitizeLine(line) + "\n").getBytes(StandardCharsets.UTF_8);
                zip.write(sanitized);
            }
        }
        long finalSize = Files.size(SafeFilePublication.requireSafeRegularFile(file, "support bundle input after read"));
        if (finalSize != expectedSize) throw new IOException("Diagnostic input changed while bundling: " + file);
        zip.closeEntry();
    }

    /**
     * Return a normalized line without allocating its entire contents on malformed or
     * attacker-controlled log files. Omit the full oversized line, not a prefix that could
     * expose credentials or split redaction tokens across chunks.
     */
    static String readBoundedDiagnosticLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder(256);
        boolean tooLong = false;
        boolean seen = false;
        for (int ch; (ch = reader.read()) != -1;) {
            seen = true;
            if (ch == '\n') break;
            if (ch == '\r') {
                reader.mark(1);
                int following = reader.read();
                if (following != '\n' && following != -1) reader.reset();
                break;
            }
            if (!tooLong) {
                if (line.length() < MAX_DIAGNOSTIC_LINE_CHARS) {
                    line.append((char) ch);
                } else {
                    tooLong = true;
                }
            }
        }
        if (!seen) return null;
        return tooLong ? OMITTED_DIAGNOSTIC_LINE : line.toString();
    }

    private void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
