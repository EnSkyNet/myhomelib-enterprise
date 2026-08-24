package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExportToDeviceUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final List<BookConverter> converters;
    private final BookResourcePort bookResourcePort;
    private final ApplicationSettingsPort settings;

    private final Map<ExportRequest.ExportFormat, String> formatExtensions = Map.of(
            ExportRequest.ExportFormat.FB2, ".fb2",
            ExportRequest.ExportFormat.FB2_ZIP, ".fb2.zip",
            ExportRequest.ExportFormat.TXT, ".txt",
            ExportRequest.ExportFormat.PDF, ".pdf",
            ExportRequest.ExportFormat.EPUB, ".epub",
            ExportRequest.ExportFormat.MOBI, ".mobi",
            ExportRequest.ExportFormat.LRF, ".lrf"
    );

    public record ExportProgress(int processed, int total, String title) { }

    public record ExportResult(int exported, int skipped, int failed, boolean cancelled, List<String> errors) {
        public static ExportResult empty() {
            return new ExportResult(0, 0, 0, false, new ArrayList<>());
        }
    }

    public Set<ExportRequest.ExportFormat> supportedFormats() {
        EnumSet<ExportRequest.ExportFormat> result = EnumSet.noneOf(ExportRequest.ExportFormat.class);
        for (ExportRequest.ExportFormat format : ExportRequest.ExportFormat.values()) {
            if (findAnyAvailableConverter(format) != null) result.add(format);
        }
        return Set.copyOf(result);
    }

    public ExportResult execute(ExportRequest request) {
        return execute(request, new AtomicBoolean(false), progress -> { });
    }

    /**
     * Batch export with cooperative cancellation between books and progress callbacks.
     * A converter already processing one book is allowed to finish so that partial
     * target files are not abandoned by forcibly interrupting third-party tools.
     */
    public ExportResult execute(ExportRequest request, AtomicBoolean cancelFlag, Consumer<ExportProgress> progress) {
        if (request == null || request.getBookIds() == null || request.getBookIds().isEmpty()) {
            log.warn("Немає книг для експорту");
            return ExportResult.empty();
        }
        AtomicBoolean cancel = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        Consumer<ExportProgress> reporter = progress == null ? p -> { } : progress;

        log.info("Початок експорту {} книг у формат {}", request.getBookIds().size(), request.getFormat());

        AtomicInteger exported = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        Path destination = request.getDestinationFolder();
        if (destination == null) {
            return new ExportResult(0, 0, request.getBookIds().size(), false, List.of("Не вказано папку призначення"));
        }
        if (!Files.exists(destination)) {
            try {
                Files.createDirectories(destination);
            } catch (Exception e) {
                log.error("Не вдалося створити папку: {}", destination, e);
                return new ExportResult(0, 0, request.getBookIds().size(), false, List.of("Не вдалося створити папку: " + e.getMessage()));
            }
        }

        int total = request.getBookIds().size();
        int processed = 0;
        for (BookId bookId : request.getBookIds()) {
            if (cancel.get()) break;
            String progressTitle = bookId.asString();
            try {
                Book book = bookQueryRepository.findById(bookId)
                        .orElseThrow(() -> new IllegalArgumentException("Книгу не знайдено: " + bookId));
                progressTitle = book.getTitle() == null ? bookId.asString() : book.getTitle();
                BookConverter converter = findConverter(request.getFormat(), book);
                if (converter == null) {
                    throw new IllegalArgumentException("Формат " + request.getFormat() +
                            " не підтримується для джерела: " + sourceName(book) +
                            " або зовнішній конвертер не налаштовано");
                }

                log.debug("Експорт книги: {}", book.getTitle());
                String fileName = generateFileName(book, request);
                Path bookDestination = destination.resolve(generateSubfolder(book)).normalize();
                Path normalizedDestination = destination.toAbsolutePath().normalize();
                if (!bookDestination.toAbsolutePath().normalize().startsWith(normalizedDestination)) {
                    throw new IllegalArgumentException("Шаблон підпапки виходить за межі папки експорту");
                }
                Files.createDirectories(bookDestination);
                Path targetFile = bookDestination.resolve(fileName + converter.getTargetExtension());

                if (Files.exists(targetFile)) {
                    switch (request.effectiveCollisionPolicy()) {
                        case SKIP -> {
                            skipped.incrementAndGet();
                            continue;
                        }
                        case RENAME -> targetFile = nextAvailableName(bookDestination, fileName, converter.getTargetExtension());
                        case OVERWRITE -> { /* converter replaces the target */ }
                    }
                }

                try (InputStream sourceStream = getBookStream(book)) {
                    converter.convert(book, sourceStream, targetFile);
                    runPostCommand(book, destination, targetFile);
                    exported.incrementAndGet();
                    log.info("Експортовано: {} -> {}", book.getTitle(), targetFile.getFileName());
                }
            } catch (Exception e) {
                failed.incrementAndGet();
                String error = String.format("Помилка експорту книги %s: %s", bookId, e.getMessage());
                errors.add(error);
                log.error(error, e);
            } finally {
                processed++;
                try { reporter.accept(new ExportProgress(Math.min(processed, total), total, progressTitle)); }
                catch (RuntimeException callbackError) { log.debug("Export progress callback failed", callbackError); }
            }
        }

        boolean cancelled = cancel.get();
        log.info("Експорт завершено: експортовано {}, пропущено {}, помилок {}, cancelled={}",
                exported.get(), skipped.get(), failed.get(), cancelled);
        return new ExportResult(exported.get(), skipped.get(), failed.get(), cancelled, List.copyOf(errors));
    }

    private Path nextAvailableName(Path folder, String baseName, String extension) {
        int counter = 1;
        Path candidate;
        do {
            candidate = folder.resolve(baseName + " (" + counter++ + ")" + extension);
        } while (Files.exists(candidate));
        return candidate;
    }

    /**
     * Отримує InputStream для книги.
     * Якщо книга є архівом – отримуємо перший FB2 з архіву.
     */
    private InputStream getBookStream(Book book) throws Exception {
        return bookResourcePort.readBookData(book)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Файл книги не знайдено або архівний запис недоступний: " + sourceName(book)));
    }

    private String sourceName(Book book) {
        if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) return book.getArchiveEntry();
        return book.getFileName() == null ? "" : book.getFileName();
    }

    private BookConverter findAnyAvailableConverter(ExportRequest.ExportFormat format) {
        String targetExt = formatExtensions.get(format);
        if (targetExt == null) return null;
        for (BookConverter converter : converters) {
            if (converter.isAvailable() && (converter.getTargetExtension().equalsIgnoreCase(targetExt)
                    || converter.getFormatName().equalsIgnoreCase(format.name()))) return converter;
        }
        return null;
    }

    private BookConverter findConverter(ExportRequest.ExportFormat format, Book book) {
        String targetExt = formatExtensions.get(format);
        if (targetExt == null) return null;
        for (BookConverter converter : converters) {
            if (converter.isAvailable() && converter.supports(book)
                    && converter.getTargetExtension().equalsIgnoreCase(targetExt)) return converter;
        }
        for (BookConverter converter : converters) {
            if (converter.isAvailable() && converter.supports(book)
                    && converter.getFormatName().equalsIgnoreCase(format.name())) return converter;
        }
        return null;
    }

    private String generateSubfolder(Book book) {
        String template = settings.get("export.subfolderTemplate", "").trim();
        if (template.isEmpty()) return "";
        return applyTemplate(template, book).replace("..", "_");
    }

    private void runPostCommand(Book book, Path destination, Path file) {
        if (!settings.getBoolean("export.runPostCommand", false)) return;
        String template = settings.get("export.postCommand", "").trim();
        if (template.isEmpty()) return;
        try {
            List<String> args = CommandTemplate.expand(template, Map.ofEntries(
                    Map.entry("%DEST%", destination.toAbsolutePath().toString()),
                    Map.entry("%TMP%", Path.of(System.getProperty("java.io.tmpdir", destination.toString())).toAbsolutePath().toString()),
                    Map.entry("%FILE%", file.toAbsolutePath().toString()),
                    Map.entry("%DESTFILE%", file.toAbsolutePath().toString()),
                    Map.entry("%FILENAME%", file.getFileName().toString()),
                    Map.entry("%TITLE%", book.getTitle() == null ? "" : book.getTitle()),
                    Map.entry("%AUTHOR%", book.authorsText() == null ? "" : book.authorsText()),
                    Map.entry("%SERIES%", book.getSeries() == null ? "" : book.getSeries()),
                    Map.entry("%EXT%", extensionOf(file.getFileName().toString())),
                    Map.entry("%BOOKID%", book.getId().asString())));
            if (!args.isEmpty()) new ProcessBuilder(args).start();
        } catch (Exception e) {
            log.warn("Post-send script failed for {}: {}", book.getTitle(), e.getMessage());
        }
    }

    private String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private String generateFileName(Book book, ExportRequest request) {
        String template = request.getCustomFileNameTemplate();
        if (template == null || template.isBlank()) template = settings.get("export.filenameTemplate", "%a - %t");
        return applyTemplate(template, book);
    }

    private String applyTemplate(String template, Book book) {
        String value = template
                .replace("%t", sanitizeFileName(book.getTitle()))
                .replace("%a", sanitizeFileName(book.authorsText()))
                .replace("%s", book.getSeries() != null ? sanitizeFileName(book.getSeries()) : "")
                .replace("%n", book.getSequenceNumber() != null ? String.valueOf(book.getSequenceNumber()) : "")
                .replace("%id", book.getId().asString());
        return sanitizePathTemplate(value);
    }

    private String sanitizePathTemplate(String value) {
        if (value == null) return "";
        String[] parts = value.replace('\\','/').split("/");
        return java.util.Arrays.stream(parts).filter(p -> !p.isBlank()).map(this::sanitizeFileName)
                .collect(java.util.stream.Collectors.joining(java.io.File.separator));
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[<>:\"/\\|?*]", "_")
                .replaceAll("\\s+", " ")
                .trim();
    }
}