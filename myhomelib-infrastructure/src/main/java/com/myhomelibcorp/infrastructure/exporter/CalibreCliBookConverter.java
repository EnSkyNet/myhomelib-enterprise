package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.conversion.ConversionFailureSignal;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.ExecutablePathSafety;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;
import com.myhomelibcorp.application.util.SafeFilePublication;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
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
            "azw", "azw3", "azw4", "cbz", "cbr", "cb7", "cbc", "chm", "djvu", "docx",
            "epub", "fb2", "fb2_zip", "fbz", "html", "htm", "htmlz", "kepub", "lit", "lrf",
            "mobi", "odt", "pdf", "prc", "pdb", "pml", "pmlz", "rb", "rtf", "snb", "tcr", "txt", "txtz");

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

    @Override public int priority() { return 100; }

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
        convertInternal(context.book(), context.sourceFormat(), context.sourceStream(), context.targetFile(), context.cancelled(), context.maxOutputBytes());
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        convertInternal(book, sourceFormat(book), sourceStream, targetFile, () -> false, Long.MAX_VALUE);
    }

    private void convertInternal(Book book,
                                 String sourceFormat,
                                 InputStream sourceStream,
                                 Path targetFile,
                                 BooleanSupplier cancelled,
                                 long maxOutputBytes) throws Exception {
        Path executable = executable().orElseThrow(() -> new IllegalStateException("calibre ebook-convert is not available"));
        checkCancelled(cancelled);

        Path sandbox = SafeFilePublication.createDirectoriesNoLinks(
                AppPaths.cacheDir().resolve("calibre").toAbsolutePath().normalize(), "Calibre cache");
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                sandbox, "mhl-calibre-", "Calibre workspace");
        String sourceExtension = sourceExtension(sourceFormat);
        Path source = workspace.resolve("input" + sourceExtension);
        Path processOutput = workspace.resolve("output" + targetExtension);
        Path normalizedTarget = targetFile.toAbsolutePath().normalize();

        try {
            SafeFilePublication.copyFresh(sourceStream, source, "Calibre staged input");
            checkCancelled(cancelled);

            List<String> argv = List.of(executable.toString(), source.toString(), processOutput.toString());
            int timeoutSeconds = Math.max(10, Math.min(3600, settings.getInt(TIMEOUT_SETTING, 300)));
            AtomicBoolean outputTooLarge = new AtomicBoolean();
            AtomicReference<Exception> unsafeOutput = new AtomicReference<>();
            BooleanSupplier guardedCancellation = () -> {
                if (cancelled.getAsBoolean()) return true;
                try {
                    SafeFilePublication.requireSafeDirectoryPath(workspace, "Calibre workspace monitor");
                    if (Files.exists(processOutput, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        SafeFilePublication.requireSafeRegularFile(processOutput, "Calibre monitored output");
                        if (Files.size(processOutput) > maxOutputBytes) {
                            outputTooLarge.set(true);
                            return true;
                        }
                    }
                    return false;
                } catch (Exception unsafe) {
                    unsafeOutput.compareAndSet(null, unsafe);
                    return true;
                }
            };
            CommandResult result;
            try {
                result = commandRunner.run(argv, sandbox, guardedCancellation, Duration.ofSeconds(timeoutSeconds));
            } catch (CancellationException cancelledError) {
                if (unsafeOutput.get() != null) {
                    throw new IllegalStateException("Unsafe calibre output path/type", unsafeOutput.get());
                }
                if (outputTooLarge.get()) {
                    throw new IllegalStateException("calibre output exceeded limit " + maxOutputBytes + " bytes");
                }
                throw cancelledError;
            }
            if (!result.output().isBlank()) {
                log.debug("calibre {} conversion output: {}", targetFormat, result.output());
            }
            if (result.exitCode() != 0) {
                throw new RetryableCalibreException("calibre ebook-convert exited with code " + result.exitCode()
                        + failureSuffix(result.output()));
            }
            checkCancelled(cancelled);
            SafeFilePublication.requireSafeRegularFile(processOutput, "Calibre converter output");
            long outputBytes = Files.size(processOutput);
            if (outputBytes > maxOutputBytes) {
                throw new IllegalStateException("calibre output exceeded limit " + maxOutputBytes + " bytes");
            }
            SafeFilePublication.moveNoReplace(processOutput, normalizedTarget, "Calibre converter publication");
        } finally {
            try {
                SafeFilePublication.cleanupPrivateWorkspace(workspace);
            } catch (IOException cleanupFailure) {
                log.warn("Cannot remove temporary calibre workspace {}", workspace, cleanupFailure);
            }
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
        for (Path path : bundledInstallCandidates(exe)) {
            Optional<Path> candidate = validateExecutable(path);
            if (candidate.isPresent()) return candidate;
        }

        Optional<Path> onPath = findOnPath(exe);
        if (onPath.isPresent()) return onPath;

        for (Path path : standardInstallCandidates(System.getProperty("os.name", ""), System.getenv())) {
            Optional<Path> candidate = validateExecutable(path);
            if (candidate.isPresent()) return candidate;
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
            Path executable = ExecutablePathSafety.requireExecutable(path, "Calibre executable");
            requireWindowsWorkerIfNeeded(executable);
            return Optional.of(executable);
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    /**
     * Frozen Windows calibre launches background jobs through calibre-parallel.exe located next
     * to ebook-convert.exe. A stripped bundle containing only ebook-convert.exe passes --version
     * but fails every real conversion with WinError 2, so that layout is not usable.
     */
    private static void requireWindowsWorkerIfNeeded(Path executable) throws IOException {
        if (executable == null || !isWindows()) return;
        Path fileName = executable.getFileName();
        if (fileName == null || !"ebook-convert.exe".equalsIgnoreCase(fileName.toString())) return;
        Path parent = executable.getParent();
        if (parent == null) throw new IOException("Calibre installation directory is unavailable");
        ExecutablePathSafety.requireExecutable(parent.resolve("calibre-parallel.exe"), "Calibre worker executable");
    }

    private static CommandResult runProcess(List<String> argv,
                                            Path workingDirectory,
                                            BooleanSupplier cancelled,
                                            Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(argv))
                .directory(workingDirectory.toFile());
        try {
            ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(
                    builder, null, timeout, MAX_CAPTURE_BYTES,
                    cancelled == null ? () -> false : cancelled);
            String output = mergeProcessOutput(result);
            return new CommandResult(result.exitCode(), output);
        } catch (ProcessExecutionSupport.ProcessTimeoutException timeoutError) {
            throw new RetryableCalibreException(
                    "calibre ebook-convert timed out after " + timeout.toSeconds() + " s", timeoutError);
        } catch (CancellationException cancelledError) {
            throw new CancellationException("Book conversion cancelled");
        } catch (IOException launchFailure) {
            throw new RetryableCalibreException("Cannot start calibre ebook-convert", launchFailure);
        }
    }

    private static String mergeProcessOutput(ProcessExecutionSupport.Result result) {
        String stdout = result.stdoutText();
        String stderr = result.stderrText();
        String joined;
        if (stdout.isBlank()) joined = stderr;
        else if (stderr.isBlank()) joined = stdout;
        else joined = stdout + "\n" + stderr;
        if (result.stdoutTruncated() || result.stderrTruncated()) joined += "\n[output truncated]";
        return joined;
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
        if ("fb2_zip".equals(normalized)) return ".fbz";
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
        return isWindows(System.getProperty("os.name", ""));
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac(String osName) {
        if (osName == null) return false;
        String normalized = osName.toLowerCase(Locale.ROOT);
        return normalized.contains("mac") || normalized.contains("darwin");
    }

    static List<Path> bundledInstallCandidates(String executable) {
        Path launch = AppPaths.launchDir().toAbsolutePath().normalize();
        List<Path> roots = List.of(
                launch.resolve("tools").normalize(),
                launch.resolve("app/tools").normalize(),
                launch.resolve("../lib/app/tools").normalize(),
                launch.resolve("../app/tools").normalize()
        );
        List<Path> result = new ArrayList<>();
        for (Path root : roots) {
            result.add(root.resolve("converters").resolve("Calibre").resolve(executable).normalize());
            result.add(root.resolve("converters").resolve("calibre").resolve(executable).normalize());
        }
        return List.copyOf(result);
    }

    static List<Path> standardInstallCandidates(String osName, java.util.Map<String, String> environment) {
        List<Path> result = new ArrayList<>();
        java.util.Map<String, String> env = environment == null ? java.util.Map.of() : environment;
        if (isWindows(osName)) {
            for (String name : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                String base = env.get(name);
                if (base == null || base.isBlank()) continue;
                result.add(Path.of(base, "Calibre2", "ebook-convert.exe"));
            }
        } else if (isMac(osName)) {
            result.add(Path.of("/Applications/calibre.app/Contents/MacOS/ebook-convert"));
        }
        return List.copyOf(result);
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

    /** Provider-local Calibre failure: AUTO mode may safely try the next source/provider. */
    private static final class RetryableCalibreException extends Exception implements ConversionFailureSignal {
        private RetryableCalibreException(String message) { super(message); }
        private RetryableCalibreException(String message, Throwable cause) { super(message, cause); }
        @Override public boolean mayTryNextProvider() { return true; }
    }
}
