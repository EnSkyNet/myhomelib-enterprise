package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.ExportCollisionContext;
import com.myhomelibcorp.application.export.ExportCollisionDecision;
import com.myhomelibcorp.application.export.ExportCollisionResolver;
import com.myhomelibcorp.application.export.ExportHistoryService;
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
import java.util.LinkedHashMap;
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
    private final BookActionProfileService actionProfileService;
    private final BookActionExecutionService actionExecutionService;
    private final ExportHistoryService historyService;

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

    public record ExportResult(int exported, int skipped, int failed, boolean cancelled, long durationMs, List<String> errors) {
        public static ExportResult empty() {
            return new ExportResult(0, 0, 0, false, 0L, List.of());
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
        return execute(request, new AtomicBoolean(false), progress -> { }, context -> ExportCollisionDecision.SKIP);
    }

    public ExportResult execute(ExportRequest request, AtomicBoolean cancelFlag, Consumer<ExportProgress> progress) {
        return execute(request, cancelFlag, progress, context -> ExportCollisionDecision.SKIP);
    }

    /**
     * Batch export with cooperative cancellation and optional per-conflict callback.
     * Third-party conversion already in progress is allowed to finish, but no new book
     * starts after cancellation.
     */
    public ExportResult execute(ExportRequest request, AtomicBoolean cancelFlag, Consumer<ExportProgress> progress,
                                ExportCollisionResolver collisionResolver) {
        long startedAt = System.nanoTime();
        int requested = request == null || request.getBookIds() == null ? 0 : request.getBookIds().size();
        if (request == null || request.getBookIds() == null || request.getBookIds().isEmpty()) {
            log.warn("Немає книг для експорту");
            return ExportResult.empty();
        }
        AtomicBoolean cancel = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        Consumer<ExportProgress> reporter = progress == null ? p -> { } : progress;
        ExportCollisionResolver resolver = collisionResolver == null ? c -> ExportCollisionDecision.SKIP : collisionResolver;

        log.info("Початок експорту {} книг у формат {} profile={}", requested, request.getFormat(), request.getProfileName());

        AtomicInteger exported = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> errors = new ArrayList<>();

        Path destination = request.getDestinationFolder();
        if (destination == null) {
            return finish(request, requested, exported.get(), skipped.get(), requested, false, startedAt,
                    List.of("Не вказано папку призначення"));
        }
        try {
            Files.createDirectories(destination);
            if (!Files.isDirectory(destination)) {
                throw new IllegalArgumentException("Шлях призначення не є папкою");
            }
            if (!Files.isWritable(destination)) {
                throw new IllegalArgumentException("Папка призначення доступна лише для читання");
            }
        } catch (Exception e) {
            log.error("Не вдалося підготувати папку: {}", destination, e);
            return finish(request, requested, 0, 0, requested, false, startedAt,
                    List.of("Не вдалося підготувати папку: " + e.getMessage()));
        }

        int processed = 0;
        int total = request.getBookIds().size();
        for (BookId bookId : request.getBookIds()) {
            if (cancel.get()) break;
            String progressTitle = bookId.asString();
            try {
                Book book = bookQueryRepository.findById(bookId)
                        .orElseThrow(() -> new IllegalArgumentException("Книгу не знайдено: " + bookId));
                progressTitle = book.getTitle() == null ? bookId.asString() : book.getTitle();
                if (bookResourcePort.locateBookFile(book).isEmpty()) {
                    throw new IllegalStateException("Книга не завантажена локально. Завантажте її перед експортом: " + progressTitle);
                }
                boolean extractRawArchiveEntry = request.isExtractOnly() && book.hasArchiveEntry();
                BookConverter converter = extractRawArchiveEntry ? null : findConverter(request.getFormat(), book);
                if (!extractRawArchiveEntry && converter == null) {
                    throw new IllegalArgumentException("Формат " + request.getFormat()
                            + " не підтримується для джерела: " + sourceName(book)
                            + " або зовнішній конвертер не налаштовано");
                }

                String targetExtension = extractRawArchiveEntry ? sourceExtension(sourceName(book)) : converter.getTargetExtension();
                if (targetExtension.isBlank()) throw new IllegalArgumentException("Не вдалося визначити розширення запису архіву");
                String fileName = generateFileName(book, request);
                Path normalizedDestination = destination.toAbsolutePath().normalize();
                Path bookDestination = destination.resolve(generateSubfolder(book, request)).normalize();
                if (!bookDestination.toAbsolutePath().normalize().startsWith(normalizedDestination)) {
                    throw new IllegalArgumentException("Шаблон підпапки виходить за межі папки експорту");
                }
                Files.createDirectories(bookDestination);
                if (!Files.isWritable(bookDestination)) {
                    throw new IllegalStateException("Папка призначення доступна лише для читання: " + bookDestination);
                }
                long expectedBytes = Math.max(1L, book.getFileSize());
                long usableBytes = Files.getFileStore(bookDestination).getUsableSpace();
                if (usableBytes < expectedBytes) {
                    throw new IllegalStateException("Недостатньо вільного місця: потрібно щонайменше "
                            + expectedBytes + " байт, доступно " + usableBytes + " байт");
                }
                Path targetFile = bookDestination.resolve(fileName + targetExtension).normalize();
                if (!targetFile.toAbsolutePath().normalize().startsWith(normalizedDestination)) {
                    throw new IllegalArgumentException("Шаблон імені виходить за межі папки експорту");
                }

                if (Files.exists(targetFile)) {
                    ExportCollisionDecision decision = collisionDecision(request, resolver,
                            new ExportCollisionContext(bookId, progressTitle, targetFile));
                    switch (decision) {
                        case SKIP -> { skipped.incrementAndGet(); continue; }
                        case RENAME -> targetFile = nextAvailableName(bookDestination, fileName, targetExtension);
                        case CANCEL -> { cancel.set(true); continue; }
                        case OVERWRITE -> { /* existing target is replaced only after staged export validates */ }
                    }
                }

                Path stagedFile = Files.createTempFile(bookDestination, ".mhl-export-", targetExtension);
                try {
                    try (InputStream sourceStream = getBookStream(book)) {
                        if (extractRawArchiveEntry) {
                            Files.copy(sourceStream, stagedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            converter.convert(book, sourceStream, stagedFile);
                        }
                    }
                    verifyExportedFile(stagedFile);
                    commitExportedFile(stagedFile, targetFile);
                    verifyExportedFile(targetFile);
                } finally {
                    Files.deleteIfExists(stagedFile);
                }
                runPostAction(book, request, destination, targetFile, errors);
                exported.incrementAndGet();
                log.info("Експортовано: {} -> {}", book.getTitle(), targetFile.getFileName());
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

        ExportResult result = finish(request, requested, exported.get(), skipped.get(), failed.get(), cancel.get(), startedAt, errors);
        log.info("Експорт завершено: exported={}, skipped={}, failed={}, cancelled={}, durationMs={}",
                result.exported(), result.skipped(), result.failed(), result.cancelled(), result.durationMs());
        return result;
    }

    private void commitExportedFile(Path stagedFile, Path targetFile) throws java.io.IOException {
        try {
            Files.move(stagedFile, targetFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(stagedFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void verifyExportedFile(Path targetFile) throws java.io.IOException {
        if (targetFile == null || !Files.isRegularFile(targetFile)) {
            throw new java.io.IOException("Файл не створено на пристрої: " + targetFile);
        }
        long size = Files.size(targetFile);
        if (size <= 0) {
            throw new java.io.IOException("Створений файл порожній: " + targetFile);
        }
        // Re-open after the converter/copy returned. This verifies that handles were closed and
        // the destination is readable before the operation is recorded as successful.
        try (InputStream ignored = Files.newInputStream(targetFile)) {
            if (ignored.read() < 0) throw new java.io.IOException("Створений файл неможливо прочитати: " + targetFile);
        }
    }

    private ExportCollisionDecision collisionDecision(ExportRequest request, ExportCollisionResolver resolver,
                                                      ExportCollisionContext context) {
        return switch (request.effectiveCollisionPolicy()) {
            case OVERWRITE -> ExportCollisionDecision.OVERWRITE;
            case SKIP -> ExportCollisionDecision.SKIP;
            case RENAME -> ExportCollisionDecision.RENAME;
            case ASK -> {
                try {
                    ExportCollisionDecision decision = resolver.resolve(context);
                    yield decision == null ? ExportCollisionDecision.SKIP : decision;
                } catch (RuntimeException e) {
                    log.warn("Collision resolver failed for {}: {}", context.existingFile(), e.getMessage());
                    yield ExportCollisionDecision.SKIP;
                }
            }
        };
    }

    private ExportResult finish(ExportRequest request, int requested, int exported, int skipped, int failed,
                                boolean cancelled, long startedAt, List<String> errors) {
        long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        ExportResult result = new ExportResult(exported, skipped, failed, cancelled, durationMs,
                errors == null ? List.of() : List.copyOf(errors));
        try { historyService.record(request, requested, exported, skipped, failed, cancelled, durationMs); }
        catch (RuntimeException e) { log.debug("Export history write failed", e); }
        return result;
    }

    private Path nextAvailableName(Path folder, String baseName, String extension) {
        int counter = 1;
        Path candidate;
        do { candidate = folder.resolve(baseName + " (" + counter++ + ")" + extension); }
        while (Files.exists(candidate));
        return candidate;
    }

    private InputStream getBookStream(Book book) throws Exception {
        return bookResourcePort.readBookData(book)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Файл книги не знайдено або архівний запис недоступний: " + sourceName(book)));
    }

    private String sourceName(Book book) {
        if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) return book.getArchiveEntry();
        return book.getFileName() == null ? "" : book.getFileName();
    }

    private String sourceExtension(String name) {
        String source = name == null ? "" : name;
        int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        int dot = source.lastIndexOf('.');
        if (dot <= slash || dot == source.length() - 1) return "";
        String ext = source.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return ext.replaceAll("[^.a-z0-9]", "");
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

    private String generateSubfolder(Book book, ExportRequest request) {
        String template = text(request.getSubfolderTemplate());
        if (template.isBlank()) template = settings.get("export.subfolderTemplate", "%a/%s").trim();
        if (template.isBlank()) template = "%a/%s";
        String result = applyTemplate(template, book).replace("..", "_");
        // Empty series segments are dropped by sanitizePathTemplate(), therefore the canonical
        // layout becomes Author/Series when a series exists and simply Author otherwise.
        return result;
    }

    private void runPostAction(Book book, ExportRequest request, Path destination, Path file, List<String> errors) {
        String profileId = text(request.getPostActionProfileId());
        if (!profileId.isBlank()) {
            BookActionProfile profile = actionProfileService.findById(profileId).orElse(null);
            if (profile == null) {
                errors.add("Post-action profile не знайдено: " + profileId);
                return;
            }
            var result = actionExecutionService.execute(profile, postActionPlaceholders(book, destination, file));
            if (!result.success()) errors.addAll(result.errors().stream().map(e -> "Post-action: " + e).toList());
            return;
        }
        runLegacyPostCommand(book, destination, file);
    }

    private Map<String,String> postActionPlaceholders(Book book, Path destination, Path file) {
        Map<String,String> values = new LinkedHashMap<>();
        values.put("%DEST%", destination.toAbsolutePath().normalize().toString());
        values.put("%TMP%", Path.of(System.getProperty("java.io.tmpdir", destination.toString())).toAbsolutePath().toString());
        values.put("%FILE%", file.toAbsolutePath().normalize().toString());
        values.put("%DESTFILE%", file.toAbsolutePath().normalize().toString());
        values.put("%FILENAME%", file.getFileName().toString());
        values.put("%DIR%", file.getParent() == null ? destination.toString() : file.getParent().toString());
        values.put("%TITLE%", text(book.getTitle()));
        values.put("%AUTHOR%", text(book.authorsText()));
        values.put("%SERIES%", text(book.getSeries()));
        values.put("%LANG%", book.getLanguage() == null ? "" : book.getLanguage().toString());
        values.put("%YEAR%", book.getYear() == null ? "" : book.getYear().toString());
        values.put("%ISBN%", book.getIsbn() == null ? "" : book.getIsbn().toString());
        values.put("%PUBLISHER%", text(book.getPublisher()));
        values.put("%EXT%", extensionOf(file.getFileName().toString()));
        values.put("%BOOKID%", book.getId().asString());
        values.put("%COLLECTION%", text(book.getCollectionRoot()));
        return Map.copyOf(values);
    }

    /** Backward compatibility only; named Stage-15 action profiles are preferred. */
    private void runLegacyPostCommand(Book book, Path destination, Path file) {
        if (!settings.getBoolean("export.runPostCommand", false)) return;
        String template = settings.get("export.postCommand", "").trim();
        if (template.isEmpty()) return;
        try {
            List<String> args = CommandTemplate.expand(template, postActionPlaceholders(book, destination, file));
            if (!args.isEmpty()) new ProcessBuilder(args)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception e) {
            log.warn("Legacy post-send script failed for {}: {}", book.getTitle(), e.getMessage());
        }
    }

    private String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private String generateFileName(Book book, ExportRequest request) {
        String template = text(request.getCustomFileNameTemplate());
        if (template.isBlank()) template = settings.get("export.filenameTemplate", "%n2 - %t").trim();
        if (template.isBlank()) template = "%n2 - %t";

        // Canonical device layout: Author/[Series]/NN - Title.ext.
        // A book outside a series has no artificial "00 -" prefix.
        if ("%n2 - %t".equals(template)) {
            String title = sanitizeFileName(book.getTitle());
            if (book.getSeries() != null && !book.getSeries().isBlank()
                    && book.getSequenceNumber() != null && book.getSequenceNumber() > 0) {
                return String.format(java.util.Locale.ROOT, "%02d - %s", book.getSequenceNumber(), title);
            }
            return title.isBlank() ? sanitizeFileName(book.getId().asString()) : title;
        }

        String result = applyTemplate(template, book);
        return result.isBlank() ? sanitizeFileName(book.getId().asString()) : result;
    }

    private String applyTemplate(String template, Book book) {
        String sequence = book.getSequenceNumber() != null && book.getSequenceNumber() > 0
                ? String.valueOf(book.getSequenceNumber()) : "";
        String sequence2 = book.getSequenceNumber() != null && book.getSequenceNumber() > 0
                ? String.format(java.util.Locale.ROOT, "%02d", book.getSequenceNumber()) : "";
        String value = text(template)
                .replace("%id", book.getId().asString())
                .replace("%lang", book.getLanguage() == null ? "" : sanitizeFileName(book.getLanguage().toString()))
                .replace("%pub", sanitizeFileName(book.getPublisher()))
                .replace("%n2", sequence2)
                .replace("%y", book.getYear() == null ? "" : book.getYear().toString())
                .replace("%t", sanitizeFileName(book.getTitle()))
                .replace("%a", sanitizeFileName(firstAuthorName(book)))
                .replace("%s", book.getSeries() != null ? sanitizeFileName(book.getSeries()) : "")
                .replace("%n", sequence);
        return sanitizePathTemplate(value);
    }


    /**
     * Device folder ownership is deterministic: when a book has multiple authors,
     * only the first author from the book metadata is used for the export path.
     */
    private String firstAuthorName(Book book) {
        if (book != null && book.getAuthors() != null) {
            for (var author : book.getAuthors()) {
                if (author == null) continue;
                String fullName = author.getFullName();
                if (fullName != null && !fullName.isBlank()) return fullName.trim();
            }
        }
        return "Без автора";
    }

    private String sanitizePathTemplate(String value) {
        if (value == null) return "";
        String[] parts = value.replace('\\','/').split("/");
        return java.util.Arrays.stream(parts).filter(p -> !p.isBlank()).map(this::sanitizeFileName)
                .collect(java.util.stream.Collectors.joining(java.io.File.separator));
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[<>:\"/\\\\|?*]", "_")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
