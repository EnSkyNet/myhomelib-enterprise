package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.ExportCollisionContext;
import com.myhomelibcorp.application.export.ExportCollisionDecision;
import com.myhomelibcorp.application.export.ExportCollisionResolver;
import com.myhomelibcorp.application.export.ExportCompletionService;
import com.myhomelibcorp.application.export.ExportHistoryService;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ExportCompletionService completionService;
    private final ConvertBookUseCase convertBookUseCase;
    private RuntimeExtensionRegistry runtimeExtensions = new RuntimeExtensionRegistry();

    @Autowired
    void setRuntimeExtensions(RuntimeExtensionRegistry runtimeExtensions) {
        this.runtimeExtensions = java.util.Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

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
        for (ConvertBookUseCase.CapabilityView view : convertBookUseCase.capabilityMatrix()) {
            if (!view.available()) continue;
            exportFormat(view.capability().targetFormat()).ifPresent(result::add);
        }
        return Set.copyOf(result);
    }

    /** Formats that every selected book can either provide as a local artifact or convert to. */
    public Set<ExportRequest.ExportFormat> supportedFormatsForBooks(List<BookId> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Set.of();
        EnumSet<ExportRequest.ExportFormat> common = EnumSet.allOf(ExportRequest.ExportFormat.class);
        for (BookId id : bookIds) {
            Book book = bookQueryRepository.findById(id).orElse(null);
            if (book == null) return Set.of();
            EnumSet<ExportRequest.ExportFormat> available = EnumSet.noneOf(ExportRequest.ExportFormat.class);
            for (BookArtifact artifact : book.getArtifacts()) {
                if (!artifact.isAvailable()) continue;
                artifactFormat(artifact).ifPresent(available::add);
            }
            for (ExportRequest.ExportFormat format : ExportRequest.ExportFormat.values()) {
                if (findConverter(format, book) != null) available.add(format);
            }
            if (book.getArtifacts().isEmpty()) {
                sourceFormat(book).ifPresent(available::add);
            }
            common.retainAll(available);
            if (common.isEmpty()) break;
        }
        return Set.copyOf(common);
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
                boolean extractRawArchiveEntry = request.isExtractOnly() && book.hasArchiveEntry();
                DirectArtifact directArtifact = null;
                Conversion conversion = null;
                ExportRequest.ExportFormat legacyDirectFormat = null;
                if (!extractRawArchiveEntry) {
                    // Respect the requested/device preference order per format. A later direct artifact must not
                    // bypass a converter for an earlier explicitly selected format (for example FB2_ZIP -> FB2).
                    for (ExportRequest.ExportFormat preferredFormat : request.effectivePreferredFormats()) {
                        directArtifact = findPreferredArtifact(book, List.of(preferredFormat));
                        if (directArtifact != null) break;
                        conversion = findPreferredConversion(book, List.of(preferredFormat));
                        if (conversion != null) break;
                        // Legacy raw copy is the last-resort fallback only when no converter exists for this format.
                        legacyDirectFormat = findLegacyDirectFormat(book, List.of(preferredFormat));
                        if (legacyDirectFormat != null) break;
                    }
                }
                if (extractRawArchiveEntry && bookResourcePort.locateBookFile(book).isEmpty()) {
                    throw new IllegalStateException("Книга не завантажена локально. Завантажте її перед експортом: " + progressTitle);
                }
                if (!extractRawArchiveEntry && directArtifact == null && legacyDirectFormat == null && conversion == null) {
                    throw new IllegalArgumentException("Жоден із форматів " + request.effectivePreferredFormats()
                            + " не доступний для джерела: " + sourceName(book)
                            + " і сумісний конвертер не налаштовано");
                }

                String targetExtension = extractRawArchiveEntry
                        ? sourceExtension(sourceName(book))
                        : directArtifact != null
                            ? formatExtensions.get(directArtifact.format())
                            : legacyDirectFormat != null
                                ? formatExtensions.get(legacyDirectFormat)
                                : conversion.converter().getTargetExtension();
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
                long expectedBytes = Math.max(1L, directArtifact == null
                        ? book.getFileSize() : directArtifact.artifact().getFile().getFileSize());
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
                    try (InputStream sourceStream = directArtifact == null
                            ? getBookStream(book) : getArtifactStream(directArtifact.artifact())) {
                        if (extractRawArchiveEntry || directArtifact != null || legacyDirectFormat != null) {
                            Files.copy(sourceStream, stagedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            conversion.converter().convert(book, sourceStream, stagedFile);
                        }
                    }
                    verifyExportedFile(stagedFile);
                    commitExportedFile(stagedFile, targetFile);
                    verifyExportedFile(targetFile);
                    completionService.complete(targetFile, request.effectiveCompletionPolicy());
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

    private InputStream getArtifactStream(BookArtifact artifact) {
        var file = artifact.getFile();
        return bookResourcePort.readBookData(file.getFileName(), file.getFolder(), file.getCollectionRoot(), file.getArchiveEntry())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Artifact недоступний локально: " + artifact.displayName()));
    }

    private DirectArtifact findPreferredArtifact(Book book, List<ExportRequest.ExportFormat> preferredFormats) {
        if (book == null || preferredFormats == null) return null;
        for (ExportRequest.ExportFormat format : preferredFormats) {
            for (BookArtifact artifact : book.getArtifacts()) {
                if (!artifact.isAvailable()) continue;
                if (artifactFormat(artifact).filter(format::equals).isPresent()) {
                    return new DirectArtifact(artifact, format);
                }
            }
        }
        return null;
    }

    private ExportRequest.ExportFormat findLegacyDirectFormat(Book book, List<ExportRequest.ExportFormat> preferredFormats) {
        if (book == null || !book.getArtifacts().isEmpty() || preferredFormats == null) return null;
        java.util.Optional<ExportRequest.ExportFormat> source = sourceFormat(book);
        if (source.isEmpty() || bookResourcePort.locateBookFile(book).isEmpty()) return null;
        for (ExportRequest.ExportFormat format : preferredFormats) {
            if (format == source.get()) return format;
        }
        return null;
    }

    private Conversion findPreferredConversion(Book book, List<ExportRequest.ExportFormat> preferredFormats) {
        if (preferredFormats == null) return null;
        for (ExportRequest.ExportFormat format : preferredFormats) {
            BookConverter converter = findConverter(format, book);
            if (converter != null) return new Conversion(format, converter);
        }
        return null;
    }

    private java.util.Optional<ExportRequest.ExportFormat> artifactFormat(BookArtifact artifact) {
        if (artifact == null) return java.util.Optional.empty();
        String name = artifact.getFile().hasArchiveEntry() ? artifact.getFile().getArchiveEntry() : artifact.getFile().getFileName();
        String normalized = (artifact.getFormat() == null ? "" : artifact.getFormat()).trim().toLowerCase(java.util.Locale.ROOT);
        String lowerName = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".fb2.zip") || normalized.equals("fb2_zip") || normalized.equals("fb2zip")) {
            return java.util.Optional.of(ExportRequest.ExportFormat.FB2_ZIP);
        }
        return switch (normalized) {
            case "fb2", "fbd" -> java.util.Optional.of(ExportRequest.ExportFormat.FB2);
            case "txt", "text" -> java.util.Optional.of(ExportRequest.ExportFormat.TXT);
            case "pdf" -> java.util.Optional.of(ExportRequest.ExportFormat.PDF);
            case "epub" -> java.util.Optional.of(ExportRequest.ExportFormat.EPUB);
            case "mobi", "azw", "azw3" -> java.util.Optional.of(ExportRequest.ExportFormat.MOBI);
            case "lrf" -> java.util.Optional.of(ExportRequest.ExportFormat.LRF);
            default -> formatFromName(lowerName);
        };
    }

    private java.util.Optional<ExportRequest.ExportFormat> sourceFormat(Book book) {
        return formatFromName(sourceName(book).toLowerCase(java.util.Locale.ROOT));
    }

    private java.util.Optional<ExportRequest.ExportFormat> formatFromName(String name) {
        if (name == null) return java.util.Optional.empty();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return java.util.Optional.of(ExportRequest.ExportFormat.FB2_ZIP);
        if (lower.endsWith(".fb2") || lower.endsWith(".fbd")) return java.util.Optional.of(ExportRequest.ExportFormat.FB2);
        if (lower.endsWith(".txt") || lower.endsWith(".text")) return java.util.Optional.of(ExportRequest.ExportFormat.TXT);
        if (lower.endsWith(".pdf")) return java.util.Optional.of(ExportRequest.ExportFormat.PDF);
        if (lower.endsWith(".epub")) return java.util.Optional.of(ExportRequest.ExportFormat.EPUB);
        if (lower.endsWith(".mobi") || lower.endsWith(".azw") || lower.endsWith(".azw3")) return java.util.Optional.of(ExportRequest.ExportFormat.MOBI);
        if (lower.endsWith(".lrf")) return java.util.Optional.of(ExportRequest.ExportFormat.LRF);
        return java.util.Optional.empty();
    }

    private record DirectArtifact(BookArtifact artifact, ExportRequest.ExportFormat format) { }

    private record Conversion(ExportRequest.ExportFormat format, BookConverter converter) { }

    private java.util.Optional<ExportRequest.ExportFormat> exportFormat(String format) {
        String normalized = com.myhomelibcorp.application.conversion.BookConversionCapability.normalizeFormat(format);
        return switch (normalized) {
            case "fb2" -> java.util.Optional.of(ExportRequest.ExportFormat.FB2);
            case "fb2_zip" -> java.util.Optional.of(ExportRequest.ExportFormat.FB2_ZIP);
            case "txt" -> java.util.Optional.of(ExportRequest.ExportFormat.TXT);
            case "pdf" -> java.util.Optional.of(ExportRequest.ExportFormat.PDF);
            case "epub" -> java.util.Optional.of(ExportRequest.ExportFormat.EPUB);
            case "mobi", "azw", "azw3" -> java.util.Optional.of(ExportRequest.ExportFormat.MOBI);
            case "lrf" -> java.util.Optional.of(ExportRequest.ExportFormat.LRF);
            default -> java.util.Optional.empty();
        };
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
        for (BookConverter converter : runtimeExtensions.mergeBookConverters(converters)) {
            if (converter.isAvailable() && converterProduces(converter, format, targetExt)) return converter;
        }
        return null;
    }

    private BookConverter findConverter(ExportRequest.ExportFormat format, Book book) {
        String targetExt = formatExtensions.get(format);
        if (targetExt == null) return null;
        for (BookConverter converter : runtimeExtensions.mergeBookConverters(converters)) {
            if (converter.isAvailable() && converter.supports(book) && converterProduces(converter, format, targetExt)) return converter;
        }
        return null;
    }

    private boolean converterProduces(BookConverter converter, ExportRequest.ExportFormat format, String targetExt) {
        String normalizedTarget = com.myhomelibcorp.application.conversion.BookConversionCapability.normalizeFormat(format.name());
        try {
            if (converter.capabilities() != null && converter.capabilities().stream().anyMatch(capability ->
                    capability != null && capability.produces(normalizedTarget))) return true;
        } catch (RuntimeException ignored) {
            // Fall back to the legacy single-target contract below.
        }
        return converter.getTargetExtension().equalsIgnoreCase(targetExt)
                || converter.getFormatName().equalsIgnoreCase(format.name());
    }

    private String generateSubfolder(Book book, ExportRequest request) {
        String template = text(request.getSubfolderTemplate());
        if (template.isBlank()) template = text(settings.get("export.subfolderTemplate", "%a/%s"));
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
        String template = text(settings.get("export.postCommand", ""));
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
        if (template.isBlank()) template = text(settings.get("export.filenameTemplate", "%n2 - %t"));
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
