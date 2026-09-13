package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.util.AppPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Adapter for original MyHomeLib-style external converters. */
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

    @Override public boolean isAvailable() {
        return !settings.get(key, "").isBlank();
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
        convertInternal(context.book(), context.sourceStream(), context.targetFile(), context.cancelled());
        context.checkCancelled();
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        convertInternal(book, sourceStream, targetFile, () -> false);
    }

    private void convertInternal(Book book, InputStream sourceStream, Path targetFile, BooleanSupplier cancelled) throws Exception {
        String command = settings.get(key, "").trim();
        if (command.isBlank()) throw new IllegalStateException("Конвертер " + name + " не налаштовано");

        Files.createDirectories(targetFile.toAbsolutePath().getParent());
        Path work = AppPaths.cacheDir().resolve("converter");
        Files.createDirectories(work);
        String sourceName = book.getArchiveEntry();
        if (sourceName == null || sourceName.isBlank()) sourceName = book.getFileName();
        String sourceExt = extensionOf(sourceName);
        Path temp = Files.createTempFile(work, "mhl-", sourceExt.isBlank() ? ".book" : sourceExt);
        try {
            Files.copy(sourceStream, temp, StandardCopyOption.REPLACE_EXISTING);
            List<String> args = CommandTemplate.expand(command, Map.of(
                    "%SRC%", temp.toAbsolutePath().toString(),
                    "%DST%", targetFile.toAbsolutePath().toString(),
                    "%TITLE%", safe(book.getTitle()),
                    "%BOOKID%", book.getId().asString()));
            if (args.isEmpty()) throw new IllegalArgumentException("Порожня команда конвертера");
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            // Drain output so the child cannot deadlock on a full pipe.
            Thread.ofVirtual().start(() -> { try (var in = p.getInputStream()) { in.transferTo(java.io.OutputStream.nullOutputStream()); } catch (Exception ignored) {} });
            int timeout = Math.max(10, settings.getInt("converter.timeoutSeconds", 300));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            while (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    p.destroyForcibly();
                    throw new CancellationException("Book conversion cancelled");
                }
                if (System.nanoTime() >= deadline) {
                    p.destroyForcibly();
                    throw new IllegalStateException("Конвертер перевищив таймаут " + timeout + " с");
                }
            }
            if (p.exitValue() != 0) throw new IllegalStateException("Конвертер завершився з кодом " + p.exitValue());
            if (!Files.isRegularFile(targetFile)) throw new IllegalStateException("Конвертер не створив файл " + targetFile);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
    private String safe(String s) { return s == null ? "" : s; }
}
