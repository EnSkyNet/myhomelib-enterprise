package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a privacy-aware diagnostic ZIP for support requests. */
@Component
public class SupportBundleService {
    private static final long MAX_LOG_FILE = 10L * 1024 * 1024;
    private static final long MAX_TOTAL_LOGS = 30L * 1024 * 1024;
    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "secret", "token", "apikey", "api_key", "credential", "auth", "encryption.key");

    private final ApplicationSettingsPort settings;

    public SupportBundleService(ApplicationSettingsPort settings) {
        this.settings = settings;
    }

    public Path create(Path output) throws IOException {
        if (output == null) throw new IllegalArgumentException("Output path is required");
        Path target = output.toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(tmp);
        try (OutputStream raw = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw), StandardCharsets.UTF_8)) {
            putText(zip, "environment.txt", environment());
            putText(zip, "settings-redacted.txt", redactedSettings());
            putText(zip, "threads.txt", threadDump());
            addValidationFiles(zip);
            addLogs(zip);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            if (e instanceof IOException io) throw io;
            throw new IOException("Cannot create support bundle", e);
        }
        try {
            return Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            return Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String environment() {
        StringBuilder out = new StringBuilder();
        line(out, "created", Instant.now().toString());
        line(out, "app", "MyHomeLib 1.0.0");
        line(out, "java.version", System.getProperty("java.version", ""));
        line(out, "java.vendor", System.getProperty("java.vendor", ""));
        line(out, "os.name", System.getProperty("os.name", ""));
        line(out, "os.version", System.getProperty("os.version", ""));
        line(out, "os.arch", System.getProperty("os.arch", ""));
        line(out, "locale", Locale.getDefault().toLanguageTag());
        line(out, "portable", Boolean.toString(AppPaths.portableMode()));
        line(out, "dataDir", AppPaths.dataDir().toString());
        line(out, "launchDir", AppPaths.launchDir().toString());
        line(out, "processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        line(out, "maxMemory", Long.toString(Runtime.getRuntime().maxMemory()));
        return out.toString();
    }

    private String redactedSettings() {
        StringBuilder out = new StringBuilder("# MyHomeLib settings (secrets redacted)\n");
        settings.findByPrefix("").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = entry.getKey();
            String value = isSecret(key) ? "<redacted>" : safeValue(entry.getValue());
            line(out, key, value);
        });
        return out.toString();
    }

    private String safeValue(String value) {
        if (value == null) return "";
        // Keep the report bounded even if a custom command or setting is unexpectedly huge.
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

    private void addValidationFiles(ZipOutputStream zip) throws IOException {
        for (String name : List.of("RELEASE_VALIDATION.txt", "PARITY_AUDIT.md", "RELEASE_NOTES_1.0.0.md")) {
            Path file = AppPaths.launchDir().resolve(name);
            if (Files.isRegularFile(file) && Files.size(file) <= 2L * 1024 * 1024) putFile(zip, "release/" + name, file, Files.size(file));
        }
    }

    private void addLogs(ZipOutputStream zip) throws IOException {
        Path dir = AppPaths.logsDir();
        if (!Files.isDirectory(dir)) return;
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).sorted((a, b) -> Long.compare(lastModified(b), lastModified(a))).forEach(files::add);
        }
        long total = 0;
        for (Path file : files) {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_LOG_FILE || total + size > MAX_TOTAL_LOGS) continue;
            putFile(zip, "logs/" + file.getFileName(), file, size);
            total += size;
        }
    }

    private long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    private void putText(ZipOutputStream zip, String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void putFile(ZipOutputStream zip, String name, Path file, long expectedSize) throws IOException {
        ZipEntry entry = new ZipEntry(name.replace('\\', '/'));
        zip.putNextEntry(entry);
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            for (int n; (n = in.read(buffer)) >= 0;) {
                if (n == 0) continue;
                copied += n;
                if (copied > expectedSize || copied > MAX_LOG_FILE && name.startsWith("logs/"))
                    throw new IOException("Diagnostic input changed while bundling: " + file);
                zip.write(buffer, 0, n);
            }
        }
        zip.closeEntry();
    }

    private void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? "" : value).append('\n');
    }
}
