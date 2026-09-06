package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a privacy-aware diagnostic ZIP for support requests. */
@Component
public class SupportBundleService {
    static final long MAX_LOG_FILE = 10L * 1024 * 1024;
    static final long MAX_TOTAL_LOGS = 30L * 1024 * 1024;
    private static final long MAX_RELEASE_FILE = 2L * 1024 * 1024;
    private static final Set<String> SECRET_WORDS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key", "credential", "auth", "encryption.key");
    private static final List<String> RELEASE_FILES = List.of(
            "RELEASE_VALIDATION.txt", "MYHOMELIB-RELEASE.md", "ARCHITECTURE.md", "MYHOMELIB-OPERATIONS.md");

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
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(tmp);
        try (OutputStream raw = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw), StandardCharsets.UTF_8)) {
            putText(zip, "environment.txt", sanitizer.sanitize(environment()));
            putText(zip, "settings-redacted.txt", sanitizer.sanitize(redactedSettings(sanitizer)));
            if (actual.includeThreadDump()) putText(zip, "threads.txt", sanitizer.sanitize(threadDump()));
            if (actual.includeReleaseDocuments()) addValidationFiles(zip, sanitizer);
            if (actual.includeLogs()) addLogs(zip, sanitizer);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            if (e instanceof IOException io) throw io;
            throw new IOException("Cannot create support bundle", e);
        }
        return AtomicFileSupport.moveReplacing(tmp, target);
    }

    /** Returns only sanitized names/sizes; file contents are not copied into the preview. */
    public SupportBundlePreview preview(SupportBundleOptions options) {
        SupportBundleOptions actual = options == null ? SupportBundleOptions.defaults() : options;
        List<SupportBundlePreview.Item> items = new ArrayList<>();
        items.add(new SupportBundlePreview.Item("environment.txt", -1, true, "шляхи псевдонімізовано"));
        items.add(new SupportBundlePreview.Item("settings-redacted.txt", -1, true, "секрети та приватні значення очищено"));
        items.add(new SupportBundlePreview.Item("threads.txt", -1, actual.includeThreadDump(), "санітизований thread dump"));

        for (String name : RELEASE_FILES) {
            Path file = AppPaths.launchDir().resolve(name);
            long size = safeSize(file);
            boolean eligible = Files.isRegularFile(file) && size >= 0 && size <= MAX_RELEASE_FILE;
            items.add(new SupportBundlePreview.Item("release/" + name, size,
                    actual.includeReleaseDocuments() && eligible,
                    eligible ? "санітизований текст" : "файл відсутній або перевищує 2 MiB"));
        }

        long accepted = 0;
        for (Path file : logFiles()) {
            long size = safeSize(file);
            boolean individuallyEligible = size > 0 && size <= MAX_LOG_FILE;
            boolean withinTotal = individuallyEligible && accepted + size <= MAX_TOTAL_LOGS;
            boolean include = actual.includeLogs() && withinTotal;
            String note;
            if (!actual.includeLogs()) note = "логи вимкнено";
            else if (!individuallyEligible) note = "пропущено: 0 B або >10 MiB";
            else if (!withinTotal) note = "пропущено: загальний ліміт 30 MiB";
            else {
                accepted += size;
                note = "line-by-line redaction";
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

    static String runtimeVersion() {
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
        return value.length() <= 4096 ? value : value.substring(0, 4096) + "…<truncated>";
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
            if (Files.isRegularFile(file) && size >= 0 && size <= MAX_RELEASE_FILE) {
                putSanitizedFile(zip, "release/" + name, file, size, sanitizer, MAX_RELEASE_FILE);
            }
        }
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
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .sorted((a, b) -> Long.compare(lastModified(b), lastModified(a)))
                    .forEach(files::add);
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(files);
    }

    private long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    private long safeSize(Path path) {
        try { return Files.isRegularFile(path) ? Files.size(path) : -1; }
        catch (IOException e) { return -1; }
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
        ZipEntry entry = new ZipEntry(name.replace('\\', '/'));
        zip.putNextEntry(entry);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                byte[] sanitized = (sanitizer.sanitizeLine(line) + "\n").getBytes(StandardCharsets.UTF_8);
                zip.write(sanitized);
            }
        }
        long finalSize = Files.size(file);
        if (finalSize != expectedSize) throw new IOException("Diagnostic input changed while bundling: " + file);
        zip.closeEntry();
    }

    private void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
