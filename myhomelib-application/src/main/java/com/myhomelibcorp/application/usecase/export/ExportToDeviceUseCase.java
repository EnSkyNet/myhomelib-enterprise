package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.shared.util.SafeFileNameSupport;
import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionCommand;
import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.conversion.CancellableBoundedInputStream;
import com.myhomelibcorp.application.conversion.ConverterSelectionPolicy;
import com.myhomelibcorp.application.conversion.ConversionFailureSignal;
import com.myhomelibcorp.application.export.ExportCollisionContext;
import com.myhomelibcorp.application.export.ExportCollisionDecision;
import com.myhomelibcorp.application.export.ExportCollisionResolver;
import com.myhomelibcorp.application.export.ExportCompletionService;
import com.myhomelibcorp.application.export.ExportHistoryService;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.application.util.SafeFilePublication;
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
import java.util.Comparator;
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
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;
    private RuntimeExtensionRegistry runtimeExtensions = new RuntimeExtensionRegistry();

    @Autowired
    void setRuntimeExtensions(RuntimeExtensionRegistry runtimeExtensions) {
        this.runtimeExtensions = java.util.Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    private final Map<ExportRequest.ExportFormat, String> formatExtensions = Map.ofEntries(
            Map.entry(ExportRequest.ExportFormat.FB2, ".fb2"),
            Map.entry(ExportRequest.ExportFormat.FB2_ZIP, ".fb2.zip"),
            Map.entry(ExportRequest.ExportFormat.TXT, ".txt"),
            Map.entry(ExportRequest.ExportFormat.PDF, ".pdf"),
            Map.entry(ExportRequest.ExportFormat.EPUB, ".epub"),
            Map.entry(ExportRequest.ExportFormat.MOBI, ".mobi"),
            Map.entry(ExportRequest.ExportFormat.AZW3, ".azw3"),
            Map.entry(ExportRequest.ExportFormat.DOCX, ".docx"),
            Map.entry(ExportRequest.ExportFormat.HTMLZ, ".htmlz"),
            Map.entry(ExportRequest.ExportFormat.KEPUB, ".kepub"),
            Map.entry(ExportRequest.ExportFormat.LIT, ".lit"),
            Map.entry(ExportRequest.ExportFormat.LRF, ".lrf"),
            Map.entry(ExportRequest.ExportFormat.PDB, ".pdb"),
            Map.entry(ExportRequest.ExportFormat.PMLZ, ".pmlz"),
            Map.entry(ExportRequest.ExportFormat.RB, ".rb"),
            Map.entry(ExportRequest.ExportFormat.RTF, ".rtf"),
            Map.entry(ExportRequest.ExportFormat.SNB, ".snb"),
            Map.entry(ExportRequest.ExportFormat.TCR, ".tcr"),
            Map.entry(ExportRequest.ExportFormat.TXTZ, ".txtz"),
            Map.entry(ExportRequest.ExportFormat.ZIP, ".zip")
    );

    public record ExportProgress(int processed, int total, String title) { }

    /** Stable UI-facing issue descriptor. Display text is resolved only in the UI language catalog. */
    public record ExportIssue(String key, List<String> arguments, String technicalDetail) {
        public ExportIssue {
            key = key == null ? "ui.export.error.generic" : key;
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            technicalDetail = technicalDetail == null ? "" : technicalDetail;
        }

        static ExportIssue of(String key, String technicalDetail, Object... arguments) {
            List<String> values = arguments == null ? List.of()
                    : java.util.Arrays.stream(arguments).map(value -> value == null ? "" : String.valueOf(value)).toList();
            return new ExportIssue(key, values, technicalDetail);
        }
    }

    public record ExportResult(int exported, int skipped, int failed, boolean cancelled, long durationMs, List<ExportIssue> issues) {
        public ExportResult { issues = issues == null ? List.of() : List.copyOf(issues); }

        /** Compatibility/diagnostics accessor. UI code must render {@link #issues()} through the language catalog. */
        public List<String> errors() {
            return issues.stream().map(issue -> issue.technicalDetail().isBlank() ? issue.key() : issue.technicalDetail()).toList();
        }

        public static ExportResult empty() {
            return new ExportResult(0, 0, 0, false, 0L, List.of());
        }
    }

    private static final class ExportUserException extends RuntimeException {
        private final ExportIssue issue;

        ExportUserException(String key, String technicalDetail, Object... arguments) {
            super(technicalDetail);
            this.issue = ExportIssue.of(key, technicalDetail, arguments);
        }

        ExportIssue issue() { return issue; }
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
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            EnumSet<ExportRequest.ExportFormat> common = EnumSet.allOf(ExportRequest.ExportFormat.class);
            for (BookId id : bookIds) {
                if (id == null) {
                    common.clear();
                    break;
                }
                Book book = bookQueryRepository.findById(id).orElse(null);
                if (book == null) {
                    common.clear();
                    break;
                }
                EnumSet<ExportRequest.ExportFormat> available = EnumSet.noneOf(ExportRequest.ExportFormat.class);
                for (BookArtifact artifact : book.getArtifacts()) {
                    if (!artifact.isAvailable()) continue;
                    artifactFormat(artifact).ifPresent(available::add);
                }
                for (ExportRequest.ExportFormat format : ExportRequest.ExportFormat.values()) {
                    if (findConversion(format, book) != null) available.add(format);
                }
                if (book.getArtifacts().isEmpty()) {
                    sourceFormat(book).ifPresent(available::add);
                }
                common.retainAll(available);
                if (common.isEmpty()) break;
            }
            Set<ExportRequest.ExportFormat> result = Set.copyOf(common);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
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
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EXPORT);
        try {
            ExportResult result = executeLocked(request, cancelFlag, progress, collisionResolver);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private ExportResult executeLocked(ExportRequest request, AtomicBoolean cancelFlag, Consumer<ExportProgress> progress,
                                       ExportCollisionResolver collisionResolver) {
        long startedAt = System.nanoTime();
        int requested = request == null || request.getBookIds() == null ? 0 : request.getBookIds().size();
        if (request == null || request.getBookIds() == null || request.getBookIds().isEmpty()) {
            log.warn("Немає книг для експорту");
            return ExportResult.empty();
        }
        requireActiveCollection(request.getCollectionId());
        AtomicBoolean cancel = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        Consumer<ExportProgress> reporter = progress == null ? p -> { } : progress;
        ExportCollisionResolver resolver = collisionResolver == null ? c -> ExportCollisionDecision.SKIP : collisionResolver;

        log.info("Початок експорту {} книг у формат {} profile={}", requested, request.getFormat(), request.getProfileName());

        AtomicInteger exported = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<ExportIssue> issues = new ArrayList<>();

        Path destination = request.getDestinationFolder();
        if (destination == null) {
            return finish(request, requested, exported.get(), skipped.get(), requested, false, startedAt,
                    List.of(ExportIssue.of("ui.export.error.destination_missing", "Destination folder is not specified")));
        }
        try {
            destination = SafeFilePublication.createDirectoriesNoLinks(
                    destination.toAbsolutePath().normalize(), "Export destination directory");
            if (!Files.isDirectory(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new ExportUserException("ui.export.error.destination_not_directory", "Destination path is not a directory", destination);
            }
            if (!Files.isWritable(destination)) {
                throw new ExportUserException("ui.export.error.destination_read_only", "Destination folder is not writable", destination);
            }
        } catch (Exception e) {
            log.error("Не вдалося підготувати папку: {}", destination, e);
            ExportIssue issue = e instanceof ExportUserException userFailure
                    ? userFailure.issue()
                    : ExportIssue.of("ui.export.error.destination_prepare_failed",
                            "Failed to prepare destination folder: " + e.getMessage(), destination);
            return finish(request, requested, 0, 0, requested, false, startedAt, List.of(issue));
        }

        int processed = 0;
        int total = request.getBookIds().size();
        for (BookId bookId : request.getBookIds()) {
            if (cancel.get()) break;
            String progressTitle = bookId.asString();
            try {
                Book book = bookQueryRepository.findById(bookId)
                        .orElseThrow(() -> new ExportUserException("ui.export.error.book_not_found", "Book not found: " + bookId, bookId));
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
                    throw new ExportUserException("ui.export.error.book_not_downloaded", "Book is not available locally: " + bookId, progressTitle);
                }
                if (!extractRawArchiveEntry && directArtifact == null && legacyDirectFormat == null && conversion == null) {
                    throw new ExportUserException("ui.export.error.no_compatible_format",
                            "No requested format is available and no compatible converter is configured: "
                                    + request.effectivePreferredFormats() + " source=" + sourceName(book),
                            progressTitle, request.effectivePreferredFormats());
                }

                String targetExtension = extractRawArchiveEntry
                        ? sourceExtension(sourceName(book))
                        : directArtifact != null
                            ? formatExtensions.get(directArtifact.format())
                            : legacyDirectFormat != null
                                ? formatExtensions.get(legacyDirectFormat)
                                : formatExtensions.get(conversion.format());
                if (targetExtension.isBlank()) throw new ExportUserException("ui.export.error.extension_unknown", "Unable to determine export file extension", progressTitle);
                String fileName = generateFileName(book, request);
                Path normalizedDestination = destination.toAbsolutePath().normalize();
                Path bookDestination = destination.resolve(generateSubfolder(book, request)).normalize();
                if (!bookDestination.toAbsolutePath().normalize().startsWith(normalizedDestination)) {
                    throw new ExportUserException("ui.export.error.subfolder_escape", "Subfolder template escapes export root", progressTitle);
                }
                bookDestination = SafeFilePublication.createDirectoriesNoLinks(
                        bookDestination.toAbsolutePath().normalize(), "Export book destination directory");
                if (!Files.isWritable(bookDestination)) {
                    throw new ExportUserException("ui.export.error.destination_read_only", "Destination folder is not writable: " + bookDestination, bookDestination);
                }
                long expectedBytes = Math.max(1L, directArtifact != null
                        ? directArtifact.artifact().getFile().getFileSize()
                        : conversion != null
                            ? maxConversionSourceBytes(book, conversion.format())
                            : book.getFileSize());
                long usableBytes = Files.getFileStore(bookDestination).getUsableSpace();
                if (usableBytes < expectedBytes) {
                    throw new ExportUserException("ui.export.error.insufficient_space",
                            "Insufficient free space: required=" + expectedBytes + " available=" + usableBytes,
                            expectedBytes, usableBytes);
                }
                Path targetFile = bookDestination.resolve(fileName + targetExtension).normalize();
                if (!targetFile.toAbsolutePath().normalize().startsWith(normalizedDestination)) {
                    throw new ExportUserException("ui.export.error.filename_escape", "File name template escapes export root", progressTitle);
                }

                String overwriteExpectedHash = null;
                if (Files.exists(targetFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    ExportCollisionDecision decision = collisionDecision(request, resolver,
                            new ExportCollisionContext(bookId, progressTitle, targetFile));
                    switch (decision) {
                        case SKIP -> { skipped.incrementAndGet(); continue; }
                        case RENAME -> targetFile = nextAvailableName(bookDestination, fileName, targetExtension);
                        case CANCEL -> { cancel.set(true); continue; }
                        case OVERWRITE -> overwriteExpectedHash = SafeFilePublication.sha256(
                                targetFile, "Existing export target");
                    }
                }

                Path stagingWorkspace = SafeFilePublication.createPrivateWorkspace(
                        bookDestination, ".mhl-export-work-", "Export staging workspace");
                try {
                    Path stagedFile;
                    if (extractRawArchiveEntry || directArtifact != null || legacyDirectFormat != null) {
                        stagedFile = stagingWorkspace.resolve("output" + targetExtension);
                        try (InputStream sourceStream = directArtifact != null
                                ? getArtifactStream(directArtifact.artifact())
                                : getBookStream(book)) {
                            SafeFilePublication.copyFresh(sourceStream, stagedFile, "Export staged copy");
                        }
                    } else {
                        stagedFile = convertWithFallback(book, conversion.format(), stagingWorkspace,
                                targetExtension, cancel);
                    }
                    verifyExportedFile(stagedFile);
                    String stagedHash = SafeFilePublication.sha256(stagedFile, "Export staged output");
                    commitExportedFile(stagedFile, targetFile, overwriteExpectedHash, stagedHash,
                            request.effectiveCompletionPolicy());
                } finally {
                    try { SafeFilePublication.cleanupPrivateWorkspace(stagingWorkspace); }
                    catch (java.io.IOException cleanupFailure) {
                        log.warn("Cannot remove disposable export staging workspace {}", stagingWorkspace, cleanupFailure);
                    }
                }
                runPostAction(book, request, destination, targetFile, issues);
                exported.incrementAndGet();
                log.info("Експортовано: {} -> {}", book.getTitle(), targetFile.getFileName());
            } catch (Exception e) {
                failed.incrementAndGet();
                ExportIssue issue = e instanceof ExportUserException userFailure
                        ? userFailure.issue()
                        : ExportIssue.of("ui.export.error.book_failed",
                                "Book export failed for " + bookId + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                                progressTitle);
                issues.add(issue);
                log.error("Book export failed for {}: {}", bookId, issue.technicalDetail(), e);
            } finally {
                processed++;
                try { reporter.accept(new ExportProgress(Math.min(processed, total), total, progressTitle)); }
                catch (RuntimeException callbackError) { log.debug("Export progress callback failed", callbackError); }
            }
        }

        ExportResult result = finish(request, requested, exported.get(), skipped.get(), failed.get(), cancel.get(), startedAt, issues);
        log.info("Експорт завершено: exported={}, skipped={}, failed={}, cancelled={}, durationMs={}",
                result.exported(), result.skipped(), result.failed(), result.cancelled(), result.durationMs());
        return result;
    }

    private void commitExportedFile(Path stagedFile, Path targetFile, String overwriteExpectedHash,
                                    String stagedHash, ExportRequest.CompletionPolicy completionPolicy)
            throws java.io.IOException {
        Path previous = null;
        boolean published = false;
        boolean success = false;
        java.io.IOException failure = null;
        try {
            if (Files.exists(targetFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (overwriteExpectedHash == null || overwriteExpectedHash.isBlank()) {
                    throw new java.nio.file.FileAlreadyExistsException(targetFile.toString());
                }
                previous = SafeFilePublication.detachIfOwned(
                        targetFile, overwriteExpectedHash, "Export overwrite");
            }
            SafeFilePublication.moveNoReplace(stagedFile, targetFile, "Export publication");
            published = true;
            // Ownership verification and optional fsync are part of the publication transaction.
            // Keep overwrite recovery evidence until both have succeeded.
            completionService.complete(targetFile, completionPolicy, stagedHash);
            success = true;
        } catch (java.io.IOException caught) {
            failure = caught;
            throw caught;
        } finally {
            if (!success) {
                boolean targetClear = !Files.exists(targetFile, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (published) {
                    try { targetClear = SafeFilePublication.removeIfOwned(
                            targetFile, stagedHash, "Export publication rollback"); }
                    catch (java.io.IOException rollbackFailure) {
                        if (failure != null) failure.addSuppressed(rollbackFailure);
                        targetClear = false;
                    }
                }
                if (previous != null && targetClear
                        && Files.exists(previous, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        if (!overwriteExpectedHash.equalsIgnoreCase(SafeFilePublication.sha256(
                                previous, "Export previous-target rollback"))) {
                            throw new java.io.IOException("Previous export target changed during rollback: " + previous);
                        }
                        SafeFilePublication.moveNoReplace(previous, targetFile, "Export previous-target restore");
                        previous = null;
                    } catch (java.io.IOException restoreFailure) {
                        if (failure != null) failure.addSuppressed(restoreFailure);
                    }
                }
            }
            if (success && previous != null) {
                try { SafeFilePublication.removeIfOwned(
                        previous, overwriteExpectedHash, "Export previous-target cleanup"); }
                catch (java.io.IOException cleanupFailure) {
                    log.warn("Committed export left previous-target evidence at {}", previous, cleanupFailure);
                }
            }
        }
    }

    private void verifyExportedFile(Path targetFile) throws java.io.IOException {
        if (targetFile == null) {
            throw new ExportUserException("ui.export.error.output_not_created",
                    "Export output file was not created: null", targetFile);
        }
        try {
            SafeFilePublication.requireSafeRegularFile(targetFile, "Export output");
        } catch (java.io.IOException unsafe) {
            throw new ExportUserException("ui.export.error.output_not_created",
                    "Export output file was not created as a safe regular file: " + targetFile, targetFile);
        }
        long size = Files.size(targetFile);
        if (size <= 0) {
            throw new ExportUserException("ui.export.error.output_empty", "Export output file is empty: " + targetFile, targetFile);
        }
        // Re-open after the converter/copy returned. This verifies that handles were closed and
        // the destination is readable before the operation is recorded as successful.
        try (InputStream ignored = Files.newInputStream(targetFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (ignored.read() < 0) throw new ExportUserException("ui.export.error.output_unreadable", "Export output file cannot be read: " + targetFile, targetFile);
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

    private void requireActiveCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            throw new IllegalArgumentException("Export request is missing collectionId");
        }
        var active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null || !expectedCollectionId.equals(active.getId())) {
            throw new IllegalStateException("Active collection changed before export started");
        }
    }

    private ExportResult finish(ExportRequest request, int requested, int exported, int skipped, int failed,
                                boolean cancelled, long startedAt, List<ExportIssue> issues) {
        long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        ExportResult result = new ExportResult(exported, skipped, failed, cancelled, durationMs,
                issues == null ? List.of() : List.copyOf(issues));
        try { historyService.record(request, requested, exported, skipped, failed, cancelled, durationMs); }
        catch (RuntimeException e) { log.debug("Export history write failed", e); }
        return result;
    }

    private Path nextAvailableName(Path folder, String baseName, String extension) {
        int counter = 1;
        Path candidate;
        do { candidate = folder.resolve(baseName + " (" + counter++ + ")" + extension); }
        while (Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS));
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
            Conversion conversion = findConversion(format, book);
            if (conversion != null) return conversion;
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
        return exportFormat(normalized).or(() -> formatFromName(lowerName));
    }

    private java.util.Optional<ExportRequest.ExportFormat> sourceFormat(Book book) {
        return formatFromName(sourceName(book).toLowerCase(java.util.Locale.ROOT));
    }

    private java.util.Optional<ExportRequest.ExportFormat> formatFromName(String name) {
        if (name == null) return java.util.Optional.empty();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return java.util.Optional.of(ExportRequest.ExportFormat.FB2_ZIP);
        for (Map.Entry<ExportRequest.ExportFormat, String> entry : formatExtensions.entrySet()) {
            if (entry.getKey() == ExportRequest.ExportFormat.FB2_ZIP) continue;
            if (lower.endsWith(entry.getValue())) return java.util.Optional.of(entry.getKey());
        }
        if (lower.endsWith(".fbd")) return java.util.Optional.of(ExportRequest.ExportFormat.FB2);
        if (lower.endsWith(".text")) return java.util.Optional.of(ExportRequest.ExportFormat.TXT);
        if (lower.endsWith(".azw")) return java.util.Optional.of(ExportRequest.ExportFormat.MOBI);
        return java.util.Optional.empty();
    }

    private record DirectArtifact(BookArtifact artifact, ExportRequest.ExportFormat format) { }

    private record ConversionSource(BookArtifact artifact, String sourceFormat) { }

    private List<BookConverter> orderedConverters() {
        String mode = settings.get("converter.mode", "AUTO");
        return runtimeExtensions.mergeBookConverters(converters).stream()
                .filter(java.util.Objects::nonNull)
                .filter(converter -> ConverterSelectionPolicy.allowed(converter, mode))
                .sorted(Comparator.comparingInt(BookConverter::priority).reversed()
                        .thenComparing(BookConverter::id, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private record Conversion(ExportRequest.ExportFormat format, BookConverter converter,
                              BookArtifact sourceArtifact, String sourceFormat) { }

    private java.util.Optional<ExportRequest.ExportFormat> exportFormat(String format) {
        String normalized = com.myhomelibcorp.application.conversion.BookConversionCapability.normalizeFormat(format);
        return switch (normalized) {
            case "fb2" -> java.util.Optional.of(ExportRequest.ExportFormat.FB2);
            case "fb2_zip" -> java.util.Optional.of(ExportRequest.ExportFormat.FB2_ZIP);
            case "txt" -> java.util.Optional.of(ExportRequest.ExportFormat.TXT);
            case "pdf" -> java.util.Optional.of(ExportRequest.ExportFormat.PDF);
            case "epub" -> java.util.Optional.of(ExportRequest.ExportFormat.EPUB);
            case "mobi", "azw" -> java.util.Optional.of(ExportRequest.ExportFormat.MOBI);
            case "azw3" -> java.util.Optional.of(ExportRequest.ExportFormat.AZW3);
            case "docx" -> java.util.Optional.of(ExportRequest.ExportFormat.DOCX);
            case "htmlz" -> java.util.Optional.of(ExportRequest.ExportFormat.HTMLZ);
            case "kepub" -> java.util.Optional.of(ExportRequest.ExportFormat.KEPUB);
            case "lit" -> java.util.Optional.of(ExportRequest.ExportFormat.LIT);
            case "lrf" -> java.util.Optional.of(ExportRequest.ExportFormat.LRF);
            case "pdb" -> java.util.Optional.of(ExportRequest.ExportFormat.PDB);
            case "pmlz" -> java.util.Optional.of(ExportRequest.ExportFormat.PMLZ);
            case "rb" -> java.util.Optional.of(ExportRequest.ExportFormat.RB);
            case "rtf" -> java.util.Optional.of(ExportRequest.ExportFormat.RTF);
            case "snb" -> java.util.Optional.of(ExportRequest.ExportFormat.SNB);
            case "tcr" -> java.util.Optional.of(ExportRequest.ExportFormat.TCR);
            case "txtz" -> java.util.Optional.of(ExportRequest.ExportFormat.TXTZ);
            case "zip" -> java.util.Optional.of(ExportRequest.ExportFormat.ZIP);
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

    private Conversion findConversion(ExportRequest.ExportFormat format, Book book) {
        List<Conversion> candidates = findConversions(format, book);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private List<Conversion> findConversions(ExportRequest.ExportFormat format, Book book) {
        String targetExt = formatExtensions.get(format);
        if (targetExt == null || book == null) return List.of();
        List<Conversion> result = new ArrayList<>();
        for (ConversionSource source : conversionSources(book)) {
            for (BookConverter converter : orderedConverters()) {
                if (!safeConverterAvailable(converter)) continue;
                if (!safeConverterSupports(converter, book, source.sourceFormat())) continue;
                if (converterProduces(converter, format, targetExt)) {
                    result.add(new Conversion(format, converter, source.artifact(), source.sourceFormat()));
                }
            }
        }
        return List.copyOf(result);
    }

    private long maxConversionSourceBytes(Book book, ExportRequest.ExportFormat format) {
        long max = Math.max(1L, book == null ? 0L : book.getFileSize());
        for (Conversion candidate : findConversions(format, book)) {
            BookArtifact artifact = candidate.sourceArtifact();
            if (artifact != null && artifact.getFile() != null) {
                max = Math.max(max, artifact.getFile().getFileSize());
            }
        }
        return max;
    }

    private Path convertWithFallback(Book book, ExportRequest.ExportFormat format, Path stagingWorkspace,
                                     String targetExtension, AtomicBoolean cancel) throws Exception {
        Exception lastFallbackFailure = null;
        int attempt = 0;
        for (Conversion candidate : findConversions(format, book)) {
            if (cancel.get()) throw new java.util.concurrent.CancellationException("Export conversion cancelled");
            Path candidateTarget = stagingWorkspace.resolve("candidate-" + (++attempt) + targetExtension);
            if (Files.exists(candidateTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(candidateTarget.toString());
            }
            try (InputStream rawSource = candidate.sourceArtifact() != null
                    ? getArtifactStream(candidate.sourceArtifact())
                    : getBookStream(book);
                 InputStream sourceStream = new CancellableBoundedInputStream(
                         rawSource, ConvertBookUseCase.DEFAULT_MAX_INPUT_BYTES, cancel::get)) {
                candidate.converter().convert(new BookConversionContext(
                        book, candidate.sourceFormat(), candidate.format().name(), sourceStream, candidateTarget,
                        cancel::get, ConvertBookUseCase.DEFAULT_MAX_OUTPUT_BYTES));
                verifyConvertedOutput(candidateTarget, ConvertBookUseCase.DEFAULT_MAX_OUTPUT_BYTES);
                return candidateTarget;
            } catch (Exception failure) {
                if (mayTryNextProvider(failure)) {
                    lastFallbackFailure = failure;
                    continue;
                }
                throw failure;
            }
        }
        if (lastFallbackFailure != null) throw lastFallbackFailure;
        throw new ExportUserException("ui.export.error.no_compatible_format",
                "No usable converter remained for export format " + format, format);
    }

    private void verifyConvertedOutput(Path output, long maxBytes) throws java.io.IOException {
        verifyExportedFile(output);
        SafeFilePublication.FileSnapshot snapshot = SafeFilePublication.snapshot(output, "Export converted output");
        if (snapshot.size() > maxBytes) {
            throw new java.io.IOException("Converted export output exceeds limit: "
                    + snapshot.size() + " > " + maxBytes + " bytes");
        }
    }

    private static boolean mayTryNextProvider(Throwable failure) {
        return ConversionFailureSignal.mayTryNextProvider(failure);
    }

    private List<ConversionSource> conversionSources(Book book) {
        List<ConversionSource> result = new ArrayList<>();
        BookArtifact preferred = book.getPreferredArtifact().filter(BookArtifact::isAvailable).orElse(null);
        if (preferred != null) addConversionSource(result, preferred);
        for (BookArtifact artifact : book.getArtifacts()) {
            if (!artifact.isAvailable()) continue;
            if (preferred != null && preferred.getId().equals(artifact.getId())) continue;
            addConversionSource(result, artifact);
        }
        if (result.isEmpty()) {
            String sourceFormat = conversionSourceFormat(sourceName(book), "");
            if (!sourceFormat.isBlank()) result.add(new ConversionSource(null, sourceFormat));
        }
        return result;
    }

    private void addConversionSource(List<ConversionSource> result, BookArtifact artifact) {
        String fileName = artifact.getFile().hasArchiveEntry()
                ? artifact.getFile().getArchiveEntry() : artifact.getFile().getFileName();
        String sourceFormat = conversionSourceFormat(fileName, artifact.getFormat());
        if (!sourceFormat.isBlank()) result.add(new ConversionSource(artifact, sourceFormat));
    }

    private String conversionSourceFormat(String fileName, String explicitFormat) {
        String normalized = BookConversionCapability.normalizeFormat(explicitFormat);
        if (!normalized.isBlank()) return normalized;
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return "fb2_zip";
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        int dot = lower.lastIndexOf('.');
        if (dot <= slash || dot == lower.length() - 1) return "";
        return BookConversionCapability.normalizeFormat(lower.substring(dot + 1));
    }

    private boolean safeConverterAvailable(BookConverter converter) {
        try { return converter != null && converter.isAvailable(); }
        catch (RuntimeException failure) { return false; }
    }

    private boolean safeConverterSupports(BookConverter converter, Book book, String sourceFormat) {
        try { return converter.supports(book, sourceFormat); }
        catch (RuntimeException failure) { return false; }
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

    private void runPostAction(Book book, ExportRequest request, Path destination, Path file, List<ExportIssue> issues) {
        String profileId = text(request.getPostActionProfileId());
        if (!profileId.isBlank()) {
            BookActionProfile profile = actionProfileService.findById(profileId).orElse(null);
            if (profile == null) {
                issues.add(ExportIssue.of("ui.export.warning.post_action_profile_missing", "Post-action profile not found: " + profileId, profileId));
                return;
            }
            var result = actionExecutionService.execute(profile, postActionPlaceholders(book, destination, file));
            if (!result.success()) {
                result.errors().forEach(error -> issues.add(ExportIssue.of(
                        "ui.export.warning.post_action_failed", "Post-action failed: " + error)));
            }
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
            List<String> tokens = CommandTemplate.parse(template);
            if (tokens.isEmpty()) return;
            BookActionCommand command = new BookActionCommand(
                    tokens.getFirst(), CommandTemplate.formatArguments(tokens.subList(1, tokens.size())), "", false);
            BookActionProfile profile = new BookActionProfile(
                    "legacy-post-command-runtime", "Legacy post-command", true, List.of(command));
            var result = actionExecutionService.execute(profile, postActionPlaceholders(book, destination, file));
            if (!result.success()) {
                log.warn("Legacy post-send script failed for {}: {}", book.getTitle(), String.join("; ", result.errors()));
            }
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
        // Keep export paths language-neutral. Empty %a is dropped by sanitizePathTemplate().
        return "";
    }

    private String sanitizePathTemplate(String value) {
        if (value == null) return "";
        String[] parts = value.replace('\\','/').split("/");
        return java.util.Arrays.stream(parts).filter(p -> !p.isBlank()).map(this::sanitizeFileName)
                .collect(java.util.stream.Collectors.joining(java.io.File.separator));
    }

    private String sanitizeFileName(String name) {
        return SafeFileNameSupport.sanitizeComponent(name, 220);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
