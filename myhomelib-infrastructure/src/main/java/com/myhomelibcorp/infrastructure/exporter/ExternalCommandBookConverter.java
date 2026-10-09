package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.ExecutablePathSafety;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Adapter for original MyHomeLib-style external converters. */
@Slf4j
public abstract class ExternalCommandBookConverter implements BookConverter {
    private final ApplicationSettingsPort settings;
    private final String key;
    private final String extension;
    private final String name;

    protected ExternalCommandBookConverter(ApplicationSettingsPort settings, String key, String extension, String name) {
        this.settings = settings;
        this.key = key;
        this.extension = extension;
        this.name = name;
    }

    @Override public int priority() { return 300; }

    @Override public boolean isAvailable() {
        String command = settings.get(key, "");
        if (command == null || command.isBlank()) return false;
        try {
            List<String> tokens = CommandTemplate.parse(command);
            return !tokens.isEmpty() && !tokens.getFirst().isBlank()
                    && ExecutablePathSafety.resolve(tokens.getFirst(), "External converter executable") != null;
        } catch (Exception invalidExecutable) {
            return false;
        }
    }

    @Override public boolean supports(Book book) { return isAvailable() && Fb2ConversionSupport.supports(book); }
    @Override public boolean supports(Book book, String sourceFormat) {
        return isAvailable() && "fb2".equals(BookConversionCapability.normalizeFormat(sourceFormat));
    }
    @Override public String getTargetExtension() { return extension; }
    @Override public String getFormatName() { return name; }
    @Override public Set<BookConversionCapability> capabilities() {
        String target = extension.startsWith(".") ? extension.substring(1) : extension;
        return Set.of(new BookConversionCapability(Set.of("fb2"), target, extension));
    }

    @Override
    public void convert(BookConversionContext context) throws Exception {
        context.checkCancelled();
        convertInternal(context.book(), context.sourceFormat(), context.sourceStream(), context.targetFile(),
                context.cancelled(), context.maxOutputBytes());
        context.checkCancelled();
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        convertInternal(book, legacySourceFormat(book), sourceStream, targetFile, () -> false, Long.MAX_VALUE);
    }

    private void convertInternal(Book book, String sourceFormat, InputStream sourceStream, Path targetFile,
                                 BooleanSupplier cancelled, long maxOutputBytes) throws Exception {
        String command = settings.get(key, "").trim();
        if (command.isBlank()) throw new IllegalStateException("Конвертер " + name + " не налаштовано");

        Path work = SafeFilePublication.createDirectoriesNoLinks(
                AppPaths.cacheDir().resolve("converter"), "External converter cache");
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                work, "mhl-external-", "External converter workspace");
        String normalizedSource = BookConversionCapability.normalizeFormat(sourceFormat);
        if (!"fb2".equals(normalizedSource)) {
            throw new IllegalArgumentException("Непідтримуваний вхідний формат для " + name + ": " + normalizedSource);
        }
        Path temp = workspace.resolve("input.fb2");
        Path processOutput = workspace.resolve("output" + extension);
        try {
            SafeFilePublication.copyFresh(sourceStream, temp, "External converter staged input");
            List<String> args = new java.util.ArrayList<>(CommandTemplate.expand(command, Map.of(
                    "%SRC%", temp.toAbsolutePath().toString(),
                    "%DST%", processOutput.toAbsolutePath().toString(),
                    "%TITLE%", safe(book.getTitle()),
                    "%BOOKID%", book.getId().asString())));
            if (args.isEmpty() || args.getFirst().isBlank()) throw new IllegalArgumentException("Порожня команда конвертера");
            args.set(0, ExecutablePathSafety.resolve(args.getFirst(), "External converter executable").toString());
            int timeout = Math.max(10, settings.getInt("converter.timeoutSeconds", 300));
            AtomicBoolean outputTooLarge = new AtomicBoolean();
            AtomicReference<Exception> unsafeOutput = new AtomicReference<>();
            BooleanSupplier guardedCancellation = () -> {
                if (cancelled.getAsBoolean()) return true;
                try {
                    SafeFilePublication.requireSafeDirectoryPath(workspace, "External converter workspace monitor");
                    if (Files.exists(processOutput, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        SafeFilePublication.requireSafeRegularFile(processOutput, "External converter monitored output");
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
            ProcessExecutionSupport.Result process;
            try {
                process = ProcessExecutionSupport.run(new ProcessBuilder(args), null,
                        Duration.ofSeconds(timeout), ProcessExecutionSupport.DEFAULT_MAX_CAPTURE_BYTES, guardedCancellation);
            } catch (CancellationException cancelledError) {
                if (unsafeOutput.get() != null) {
                    throw new IllegalStateException("Небезпечний шлях/тип файла результату зовнішнього конвертера",
                            unsafeOutput.get());
                }
                if (outputTooLarge.get()) {
                    throw new IllegalStateException("Результат зовнішнього конвертера перевищив ліміт "
                            + maxOutputBytes + " байт");
                }
                throw cancelledError;
            } catch (ProcessExecutionSupport.ProcessTimeoutException timeoutError) {
                throw new IllegalStateException("Конвертер перевищив таймаут " + timeout + " с", timeoutError);
            }
            if (process.exitCode() != 0) {
                throw new IllegalStateException("Конвертер завершився з кодом " + process.exitCode());
            }
            SafeFilePublication.FileSnapshot outputSnapshot =
                    SafeFilePublication.snapshot(processOutput, "External converter output");
            long outputBytes = outputSnapshot.size();
            if (outputBytes <= 0) {
                throw new IllegalStateException("Зовнішній конвертер створив порожній файл результату");
            }
            if (outputBytes > maxOutputBytes) {
                throw new IllegalStateException("Результат зовнішнього конвертера перевищив ліміт "
                        + maxOutputBytes + " байт");
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    processOutput, targetFile, null, outputSnapshot.sha256(), "External converter publication");
        } finally {
            try {
                SafeFilePublication.cleanupPrivateWorkspace(workspace);
            } catch (Exception cleanupFailure) {
                log.warn("Cannot remove temporary converter workspace {}", workspace, cleanupFailure);
            }
        }
    }

    private String legacySourceFormat(Book book) {
        String sourceName = book == null ? "" : book.getArchiveEntry();
        if (sourceName == null || sourceName.isBlank()) sourceName = book == null ? "" : book.getFileName();
        String sourceExt = extensionOf(sourceName);
        return ".fb2".equalsIgnoreCase(sourceExt) || ".fbd".equalsIgnoreCase(sourceExt) ? "fb2" : "";
    }

    private String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
    private String safe(String s) { return s == null ? "" : s; }
}
