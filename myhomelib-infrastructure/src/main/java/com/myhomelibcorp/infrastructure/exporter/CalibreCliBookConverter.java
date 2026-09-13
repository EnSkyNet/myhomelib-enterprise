package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Optional calibre {@code ebook-convert} adapter built on the MHL-506 conversion SPI. */
@Slf4j
public final class CalibreCliBookConverter implements BookConverter {
    static final String EXECUTABLE_SETTING = "converter.calibre.executable";
    static final String TIMEOUT_SETTING = "converter.calibre.timeoutSeconds";
    private static final int MAX_CAPTURE_BYTES = 64 * 1024;
    private static final int MAX_FAILURE_TEXT = 2048;
    private static final Set<String> SOURCE_FORMATS = Set.of(
            "fb2", "fb2_zip", "epub", "mobi", "azw", "azw3", "txt", "html", "htm", "rtf", "docx", "pdf");

    private final ApplicationSettingsPort settings;
    private final String targetFormat;
    private final String targetExtension;
    private final ExecutableResolver executableResolver;
    private final CommandRunner commandRunner;

    public CalibreCliBookConverter(ApplicationSettingsPort settings, String targetFormat, String targetExtension) {
        this(settings, targetFormat, targetExtension, CalibreCliBookConverter::resolveDefaultExecutable,
                CalibreCliBookConverter::runProcess);
    }

    CalibreCliBookConverter(ApplicationSettingsPort settings,
                            String targetFormat,
                            String targetExtension,
                            ExecutableResolver executableResolver,
                            CommandRunner commandRunner) {
        this.settings = settings;
        this.targetFormat = BookConversionCapability.normalizeFormat(targetFormat);
        this.targetExtension = normalizeExtension(targetExtension);
        this.executableResolver = executableResolver;
        this.commandRunner = commandRunner;
        if (this.targetFormat.isBlank()) throw new IllegalArgumentException("targetFormat cannot be blank");
    }

    @Override public String id() { return "calibre-cli:" + targetFormat; }

    @Override
    public boolean isAvailable() {
        return executable().isPresent();
    }

    @Override
    public boolean supports(Book book) {
        return isAvailable() && SOURCE_FORMATS.contains(sourceFormat(book));
    }

    @Override
    public boolean supports(Book book, String sourceFormat) {
        return isAvailable() && SOURCE_FORMATS.contains(BookConversionCapability.normalizeFormat(sourceFormat));
    }

    @Override public String getTargetExtension() { return targetExtension; }
    @Override public String getFormatName() { return targetFormat.toUpperCase(Locale.ROOT); }

    @Override
    public Set<BookConversionCapability> capabilities() {
        return Set.of(new BookConversionCapability(SOURCE_FORMATS, targetFormat, targetExtension));
    }

    @Override
    public void convert(BookConversionContext context) throws Exception {
        String requested = BookConversionCapability.normalizeFormat(context.targetFormat());
        if (!targetFormat.equals(requested)) {
            throw new IllegalArgumentException("Calibre converter target mismatch: " + requested + " != " + targetFormat);
        }
        convertInternal(context.book(), context.sourceFormat(), context.sourceStream(), context.targetFile(), context.cancelled());
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        convertInternal(book, sourceFormat(book), sourceStream, targetFile, () -> false);
    }

    private void convertInternal(Book book,
                                 String sourceFormat,
                                 InputStream sourceStream,
                                 Path targetFile,
                                 BooleanSupplier cancelled) throws Exception {
        Path executable = executable().orElseThrow(() -> new IllegalStateException("calibre ebook-convert is not available"));
        checkCancelled(cancelled);

        Path sandbox = AppPaths.cacheDir().resolve("calibre").toAbsolutePath().normalize();
        Files.createDirectories(sandbox);
        String sourceExtension = sourceExtension(sourceFormat);
        Path source = Files.createTempFile(sandbox, "mhl-calibre-", sourceExtension).toAbsolutePath().normalize();
        if (!source.startsWith(sandbox)) throw new IllegalStateException("calibre source escaped converter sandbox");

        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null) throw new IllegalArgumentException("Target file must have a parent directory");
        Files.createDirectories(parent);

        try {
            Files.copy(sourceStream, source, StandardCopyOption.REPLACE_EXISTING);
            checkCancelled(cancelled);

            // Application callers hand converters an owned staging path that may already exist as an empty temp file.
            // calibre expects to create/replace its output itself, so remove only that supplied staging file before launch.
            Files.deleteIfExists(normalizedTarget);
            List<String> argv = List.of(executable.toString(), source.toString(), normalizedTarget.toString());
            int timeoutSeconds = Math.max(10, Math.min(3600, settings.getInt(TIMEOUT_SETTING, 300)));
            CommandResult result = commandRunner.run(argv, sandbox, cancelled, Duration.ofSeconds(timeoutSeconds));
            if (!result.output().isBlank()) {
                log.debug("calibre {} conversion output: {}", targetFormat, result.output());
            }
            if (result.exitCode() != 0) {
                throw new IllegalStateException("calibre ebook-convert exited with code " + result.exitCode()
                        + failureSuffix(result.output()));
            }
            checkCancelled(cancelled);
            if (!Files.isRegularFile(normalizedTarget)) {
                throw new IllegalStateException("calibre ebook-convert did not create output file");
            }
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private Optional<Path> executable() {
        String configured = settings.get(EXECUTABLE_SETTING, "");
        return executableResolver.resolve(configured == null ? "" : configured.trim());
    }

    private static Optional<Path> resolveDefaultExecutable(String configured) {
        String value = stripOuterQuotes(configured == null ? "" : configured.trim());
        if (!value.isBlank()) {
            Optional<Path> explicit = resolveCandidate(value);
            if (explicit.isPresent()) return explicit;
            return Optional.empty();
        }

        String exe = isWindows() ? "ebook-convert.exe" : "ebook-convert";
        Optional<Path> onPath = findOnPath(exe);
        if (onPath.isPresent()) return onPath;

        if (isWindows()) {
            for (String env : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                String base = System.getenv(env);
                if (base == null || base.isBlank()) continue;
                Optional<Path> candidate = validateExecutable(Path.of(base, "Calibre2", "ebook-convert.exe"));
                if (candidate.isPresent()) return candidate;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> resolveCandidate(String value) {
        boolean looksLikePath = value.contains("/") || value.contains("\\") || Path.of(value).isAbsolute();
        if (looksLikePath) return validateExecutable(Path.of(value).toAbsolutePath().normalize());
        return findOnPath(value);
    }

    private static Optional<Path> findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return Optional.empty();
        for (String part : path.split(java.io.File.pathSeparator)) {
            if (part == null || part.isBlank()) continue;
            try {
                Optional<Path> candidate = validateExecutable(Path.of(part).resolve(executable).toAbsolutePath().normalize());
                if (candidate.isPresent()) return candidate;
            } catch (RuntimeException ignored) {
                // Ignore malformed PATH entries.
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> validateExecutable(Path path) {
        try {
            if (!Files.isRegularFile(path)) return Optional.empty();
            if (!isWindows() && !Files.isExecutable(path)) return Optional.empty();
            return Optional.of(path);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static CommandResult runProcess(List<String> argv,
                                            Path workingDirectory,
                                            BooleanSupplier cancelled,
                                            Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(argv))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        AtomicReference<String> output = new AtomicReference<>("");
        Thread reader = Thread.ofVirtual().start(() -> output.set(readBounded(process.getInputStream())));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    throw new CancellationException("Book conversion cancelled");
                }
                if (System.nanoTime() >= deadline) {
                    process.destroyForcibly();
                    throw new IllegalStateException("calibre ebook-convert timed out after " + timeout.toSeconds() + " s");
                }
            }
            reader.join(2000);
            return new CommandResult(process.exitValue(), output.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw interrupted;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readBounded(InputStream in) {
        try (InputStream stream = in; ByteArrayOutputStream captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int remaining = MAX_CAPTURE_BYTES;
            while ((read = stream.read(buffer)) >= 0) {
                if (read <= 0) continue;
                if (remaining > 0) {
                    int keep = Math.min(read, remaining);
                    captured.write(buffer, 0, keep);
                    remaining -= keep;
                }
            }
            String text = captured.toString(StandardCharsets.UTF_8);
            return remaining == 0 ? text + "\n[output truncated]" : text;
        } catch (IOException failure) {
            return "[unable to capture calibre output: " + failure.getClass().getSimpleName() + "]";
        }
    }

    private static String failureSuffix(String output) {
        if (output == null || output.isBlank()) return "";
        String compact = output.strip().replaceAll("[\\r\\n]+", " | ");
        if (compact.length() > MAX_FAILURE_TEXT) compact = compact.substring(0, MAX_FAILURE_TEXT) + "…";
        return ": " + compact;
    }

    private static String sourceFormat(Book book) {
        if (book == null) return "";
        String name = book.getArchiveEntry();
        if (name == null || name.isBlank()) name = book.getFileName();
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return "fb2_zip";
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : BookConversionCapability.normalizeFormat(lower.substring(dot + 1));
    }

    private static String sourceExtension(String format) {
        String normalized = BookConversionCapability.normalizeFormat(format);
        if ("fb2_zip".equals(normalized)) return ".fb2.zip";
        String safe = normalized.replaceAll("[^a-z0-9]", "");
        return safe.isBlank() ? ".book" : "." + safe;
    }

    private static String normalizeExtension(String extension) {
        String value = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) throw new IllegalArgumentException("targetExtension cannot be blank");
        if (!value.startsWith(".")) value = "." + value;
        if (!value.matches("\\.[a-z0-9][a-z0-9._-]{0,15}")) {
            throw new IllegalArgumentException("Unsafe target extension: " + extension);
        }
        return value;
    }

    private static String stripOuterQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @FunctionalInterface
    interface ExecutableResolver { Optional<Path> resolve(String configured); }

    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(List<String> argv, Path workingDirectory, BooleanSupplier cancelled, Duration timeout) throws Exception;
    }

    record CommandResult(int exitCode, String output) {
        CommandResult { output = output == null ? "" : output; }
    }
}
