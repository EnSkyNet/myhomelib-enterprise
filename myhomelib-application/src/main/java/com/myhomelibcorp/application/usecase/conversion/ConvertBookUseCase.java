package com.myhomelibcorp.application.usecase.conversion;

import com.myhomelibcorp.shared.util.SafeFileNameSupport;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.conversion.ConverterSelectionPolicy;
import com.myhomelibcorp.application.conversion.ConversionFailureSignal;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.book.BookArtifactState;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * MHL-506 provider-neutral conversion job orchestration.
 *
 * <p>The application owns temp files, limits, crash-safe finalization and artifact
 * registration. Converter providers only transform one bounded source stream into the
 * staged target supplied by the application.</p>
 */
@Component
public class ConvertBookUseCase {

    public static final long DEFAULT_MAX_INPUT_BYTES = 512L * 1024L * 1024L;
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 1024L * 1024L * 1024L;

    private final BookQueryRepository books;
    private final List<BookConverter> converters;
    private final BookResourcePort resources;
    private final CommittedCatalogMutationService mutations;
    private final LibraryOperationCoordinator operationCoordinator;
    private RuntimeExtensionRegistry runtimeExtensions = new RuntimeExtensionRegistry();
    private ApplicationSettingsPort settings;

    /** Compatibility constructor for focused non-Spring tests and integrations. */
    public ConvertBookUseCase(BookQueryRepository books, List<BookConverter> converters,
                              BookResourcePort resources, CommittedCatalogMutationService mutations) {
        this(books, converters, resources, mutations, new LibraryOperationCoordinator());
    }

    @Autowired
    public ConvertBookUseCase(BookQueryRepository books, List<BookConverter> converters,
                              BookResourcePort resources, CommittedCatalogMutationService mutations,
                              LibraryOperationCoordinator operationCoordinator) {
        this.books = books;
        this.converters = converters == null ? List.of() : List.copyOf(converters);
        this.resources = resources;
        this.mutations = mutations;
        this.operationCoordinator = operationCoordinator;
    }

    @Autowired
    void setRuntimeExtensions(RuntimeExtensionRegistry runtimeExtensions) {
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    @Autowired
    void setApplicationSettings(ApplicationSettingsPort settings) {
        this.settings = settings;
    }

    public record Request(BookId bookId,
                          String targetFormat,
                          Path outputDirectory,
                          boolean makePreferred,
                          long maxInputBytes,
                          long maxOutputBytes,
                          BooleanSupplier cancelled) {
        public Request {
            Objects.requireNonNull(bookId, "bookId");
            targetFormat = BookConversionCapability.normalizeFormat(targetFormat);
            if (targetFormat.isBlank()) throw new IllegalArgumentException("Target format is required");
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            maxInputBytes = maxInputBytes <= 0 ? DEFAULT_MAX_INPUT_BYTES : maxInputBytes;
            maxOutputBytes = maxOutputBytes <= 0 ? DEFAULT_MAX_OUTPUT_BYTES : maxOutputBytes;
            cancelled = cancelled == null ? () -> false : cancelled;
        }

        public static Request withDefaults(BookId bookId, String targetFormat, Path outputDirectory) {
            return new Request(bookId, targetFormat, outputDirectory, false,
                    DEFAULT_MAX_INPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES, () -> false);
        }
    }

    public record CapabilityView(String converterId,
                                 boolean available,
                                 BookConversionCapability capability) { }

    public record Result(String converterId,
                         BookArtifact artifact,
                         long durationMs) { }

    public List<CapabilityView> capabilityMatrix() {
        List<CapabilityView> matrix = new ArrayList<>();
        for (BookConverter converter : orderedConverters()) {
            boolean available = safeAvailable(converter);
            for (BookConversionCapability capability : safeCapabilities(converter)) {
                matrix.add(new CapabilityView(converter.id(), available, capability));
            }
        }
        return List.copyOf(matrix);
    }

    /**
     * Capabilities that can actually be used for the selected book. This prevents the UI from
     * advertising a target format merely because a converter exists globally when none of the
     * book's available artifacts can be accepted by that converter.
     */
    public List<CapabilityView> capabilityMatrix(BookId bookId) {
        Objects.requireNonNull(bookId, "bookId");
        Book book = books.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + bookId));
        List<Source> sources = sourceCandidates(book);
        List<CapabilityView> matrix = new ArrayList<>();
        for (BookConverter converter : orderedConverters()) {
            boolean available = safeAvailable(converter);
            for (BookConversionCapability capability : safeCapabilities(converter)) {
                boolean compatible = available && sources.stream().anyMatch(source ->
                        capability.supportsSource(source.format()) && safeSupports(converter, book, source.format()));
                if (compatible) matrix.add(new CapabilityView(converter.id(), true, capability));
            }
        }
        return List.copyOf(matrix);
    }

    public Result execute(Request request) throws Exception {
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.CONVERSION);
        try {
            Result result = executeLocked(request);
            lease.markCompleted("");
            return result;
        } catch (Exception failure) {
            lease.markFailed(failure);
            throw failure;
        } catch (Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private Result executeLocked(Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        long startedAt = System.nanoTime();
        checkCancelled(request.cancelled());

        Book book = books.findById(request.bookId())
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + request.bookId()));
        List<SelectedJob> candidates = selectJobs(book, request.targetFormat());

        Path root = SafeFilePublication.createDirectoriesNoLinks(
                request.outputDirectory().toAbsolutePath().normalize(), "Conversion output directory");
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(root)) {
            throw new IllegalStateException("Conversion output directory is not writable: " + root);
        }

        SelectedJob successfulJob = null;
        Path staged = null;
        Path successfulWorkspace = null;
        Exception lastFallbackFailure = null;
        for (SelectedJob candidate : candidates) {
            checkCancelled(request.cancelled());
            Source source = candidate.source();
            if (source.sizeBytes() > 0 && source.sizeBytes() > request.maxInputBytes()) {
                throw new IllegalStateException("Conversion input exceeds limit: " + source.sizeBytes()
                        + " > " + request.maxInputBytes());
            }

            String candidateExtension = safeExtension(candidate.converter().capability().targetExtension());
            Path candidateWorkspace = SafeFilePublication.createPrivateWorkspace(
                    root, ".mhl-convert-work-", "Conversion staging workspace");
            Path candidateStaged = candidateWorkspace.resolve("output" + candidateExtension);
            boolean keepCandidate = false;
            try {
                try (InputStream raw = source.open();
                     InputStream bounded = new CancellableBoundedInputStream(raw, request.maxInputBytes(), request.cancelled())) {
                    BookConversionContext context = new BookConversionContext(
                            book, source.format(), request.targetFormat(), bounded, candidateStaged,
                            request.cancelled(), request.maxOutputBytes());
                    candidate.converter().converter().convert(context);
                } catch (Exception failure) {
                    if (mayTryNextProvider(failure)) {
                        lastFallbackFailure = failure;
                        continue;
                    }
                    throw failure;
                }

                checkCancelled(request.cancelled());
                // Invalid/empty/oversized output is a hard stop by policy; do not hide it with fallback.
                validateOutput(candidateStaged, request.maxOutputBytes());
                successfulJob = candidate;
                staged = candidateStaged;
                successfulWorkspace = candidateWorkspace;
                keepCandidate = true;
                break;
            } finally {
                if (!keepCandidate) SafeFilePublication.cleanupPrivateWorkspace(candidateWorkspace);
            }
        }

        if (successfulJob == null || staged == null) {
            if (lastFallbackFailure != null) throw lastFallbackFailure;
            throw new IllegalArgumentException("No usable converter for target " + request.targetFormat());
        }

        Source source = successfulJob.source();
        SelectedConverter selected = successfulJob.converter();
        String artifactId = "conversion:" + UUID.randomUUID();
        String extension = safeExtension(selected.capability().targetExtension());
        String finalName = safeBaseName(book.getTitle(), book.getId()) + "-"
                + artifactId.substring(artifactId.length() - 8) + extension;
        Path finalFile = root.resolve(finalName).normalize();
        if (!finalFile.startsWith(root)) throw new IllegalArgumentException("Unsafe conversion target path");

        boolean registered = false;
        boolean finalMoved = false;
        Exception publicationFailure = null;
        String stagedSha256 = null;
        try {
            SafeFilePublication.FileSnapshot stagedSnapshot =
                    SafeFilePublication.snapshot(staged, "Conversion staged output");
            stagedSha256 = stagedSnapshot.sha256();
            SafeFilePublication.moveNoReplace(staged, finalFile, "Conversion artifact publication");
            finalMoved = true;
            SafeFilePublication.FileSnapshot publishedSnapshot =
                    validateOutput(finalFile, request.maxOutputBytes());

            long size = publishedSnapshot.size();
            String sha256 = publishedSnapshot.sha256();
            if (!stagedSnapshot.sha256().equalsIgnoreCase(sha256) || stagedSnapshot.size() != size) {
                throw new IOException("Published conversion artifact fingerprint mismatch");
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("conversion.provider", selected.converter().id());
            metadata.put("conversion.sourceFormat", source.format());
            metadata.put("conversion.targetFormat", request.targetFormat());
            metadata.put("conversion.createdAt", Instant.now().toString());
            if (source.artifactId() != null && !source.artifactId().isBlank()) {
                metadata.put("conversion.sourceArtifactId", source.artifactId());
            }

            BookArtifact artifact = BookArtifact.builder()
                    .id(artifactId)
                    .sourceId("conversion:" + selected.converter().id())
                    .name(finalName)
                    .mediaType(mediaType(request.targetFormat()))
                    .format(request.targetFormat())
                    .file(new BookFile(finalName, "", "", size, root.toString()))
                    .sha256(sha256)
                    .contentFingerprint(sha256)
                    .remote(false)
                    .local(true)
                    .state(BookArtifactState.AVAILABLE)
                    .metadata(metadata)
                    .build();

            mutations.upsertArtifact(book.getId(), artifact, request.makePreferred());
            registered = true;

            long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            return new Result(selected.converter().id(), artifact, durationMs);
        } catch (Exception failure) {
            publicationFailure = failure;
            throw failure;
        } finally {
            if (finalMoved && !registered && stagedSha256 != null) {
                try { SafeFilePublication.removeIfOwned(finalFile, stagedSha256, "Conversion artifact rollback"); }
                catch (IOException rollbackFailure) {
                    if (publicationFailure != null) publicationFailure.addSuppressed(rollbackFailure);
                    else throw rollbackFailure;
                }
            }
            if (successfulWorkspace != null) {
                try { SafeFilePublication.cleanupPrivateWorkspace(successfulWorkspace); }
                catch (IOException cleanupFailure) {
                    if (publicationFailure != null) publicationFailure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    private List<SelectedJob> selectJobs(Book book, String targetFormat) {
        List<SelectedJob> jobs = new ArrayList<>();
        List<Source> sources = sourceCandidates(book);
        for (Source source : sources) {
            for (BookConverter converter : orderedConverters()) {
                if (!safeAvailable(converter) || !safeSupports(converter, book, source.format())) continue;
                for (BookConversionCapability capability : safeCapabilities(converter)) {
                    if (capability.produces(targetFormat) && capability.supportsSource(source.format())) {
                        jobs.add(new SelectedJob(source, new SelectedConverter(converter, capability)));
                        break;
                    }
                }
            }
        }
        if (!jobs.isEmpty()) return List.copyOf(jobs);
        String formats = sources.stream().map(Source::format).distinct().toList().toString();
        throw new IllegalArgumentException("No converter for sources " + formats + " -> " + targetFormat);
    }

    private static boolean mayTryNextProvider(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current instanceof ConversionFailureSignal signal) return signal.mayTryNextProvider();
            current = current.getCause();
        }
        return false;
    }

    private List<Source> sourceCandidates(Book book) {
        List<Source> result = new ArrayList<>();
        Optional<BookArtifact> preferred = book.getPreferredArtifact().filter(BookArtifact::isAvailable);
        preferred.ifPresent(artifact -> result.add(sourceFromArtifact(artifact)));
        for (BookArtifact artifact : book.getArtifacts()) {
            if (!artifact.isAvailable()) continue;
            if (preferred.isPresent() && preferred.get().getId().equals(artifact.getId())) continue;
            result.add(sourceFromArtifact(artifact));
        }
        if (result.isEmpty()) {
            String format = sourceFormat("", book.getFile());
            result.add(new Source(format, book.getFileSize(), null, () -> resources.readBookData(book)
                    .orElseThrow(() -> new IllegalArgumentException("Book source is not readable: " + book.getId()))));
        }
        return List.copyOf(result);
    }

    private Source sourceFromArtifact(BookArtifact artifact) {
        String format = sourceFormat(artifact.getFormat(), artifact.getFile());
        return new Source(format, artifact.getFile().getFileSize(), artifact.getId(), () -> resources.readBookData(
                artifact.getFile().getFileName(), artifact.getFile().getFolder(),
                artifact.getFile().getCollectionRoot(), artifact.getFile().getArchiveEntry())
                .orElseThrow(() -> new IllegalArgumentException("Source artifact is not readable: " + artifact.displayName())));
    }

    private List<BookConversionCapability> safeCapabilities(BookConverter converter) {
        try {
            var capabilities = converter.capabilities();
            return capabilities == null ? List.of() : capabilities.stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    private boolean safeAvailable(BookConverter converter) {
        try { return converter != null && converter.isAvailable(); }
        catch (RuntimeException failure) { return false; }
    }

    private boolean safeSupports(BookConverter converter, Book book, String sourceFormat) {
        try { return converter.supports(book, sourceFormat); }
        catch (RuntimeException failure) { return false; }
    }

    private static String sourceFormat(String explicit, BookFile file) {
        String normalized = BookConversionCapability.normalizeFormat(explicit);
        if (!normalized.isBlank()) return normalized;
        String name = file == null ? "" : (file.hasArchiveEntry() ? file.getArchiveEntry() : file.getFileName());
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return "fb2_zip";
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && dot + 1 < lower.length()
                ? BookConversionCapability.normalizeFormat(lower.substring(dot + 1)) : "";
    }

    private static String safeBaseName(String title, BookId id) {
        String base = SafeFileNameSupport.sanitizeComponent(title, 220);
        if (base.isBlank()) base = SafeFileNameSupport.sanitizeComponent("book-" + id.asString(), 220);
        return base;
    }

    private static String safeExtension(String extension) {
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if (!ext.startsWith(".")) ext = "." + ext;
        if (!ext.matches("\\.[a-z0-9][a-z0-9._-]{0,15}")) {
            throw new IllegalArgumentException("Unsafe conversion extension: " + extension);
        }
        return ext;
    }

    private static SafeFilePublication.FileSnapshot validateOutput(Path file, long maxOutputBytes) throws IOException {
        SafeFilePublication.FileSnapshot snapshot =
                SafeFilePublication.snapshot(file, "Converter output");
        long size = snapshot.size();
        if (size <= 0) throw new IOException("Converter produced an empty output file");
        if (size > maxOutputBytes) {
            throw new IOException("Conversion output exceeds limit: " + size + " > " + maxOutputBytes);
        }
        return snapshot;
    }

    private static String mediaType(String format) {
        return switch (BookConversionCapability.normalizeFormat(format)) {
            case "epub" -> "application/epub+zip";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "fb2" -> "application/x-fictionbook+xml";
            case "fb2_zip" -> "application/zip";
            case "mobi", "azw", "azw3" -> "application/x-mobipocket-ebook";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "rtf" -> "application/rtf";
            case "htmlz", "zip", "txtz" -> "application/zip";
            case "lrf" -> "application/x-sony-bbeb";
            default -> "application/octet-stream";
        };
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
    }

    @FunctionalInterface
    private interface InputStreamFactory { InputStream open() throws Exception; }

    private record Source(String format, long sizeBytes, String artifactId, InputStreamFactory opener) {
        InputStream open() throws Exception { return opener.open(); }
    }

    private List<BookConverter> orderedConverters() {
        String mode = settings == null ? "AUTO" : settings.get("converter.mode", "AUTO");
        return runtimeExtensions.mergeBookConverters(converters).stream()
                .filter(Objects::nonNull)
                .filter(converter -> ConverterSelectionPolicy.allowed(converter, mode))
                .sorted(Comparator.comparingInt(BookConverter::priority).reversed()
                        .thenComparing(BookConverter::id, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private record SelectedConverter(BookConverter converter, BookConversionCapability capability) { }
    private record SelectedJob(Source source, SelectedConverter converter) { }

    /** Bounded source wrapper: providers cannot read past the job input limit and reads observe cancellation. */
    private static final class CancellableBoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private final BooleanSupplier cancelled;
        private long count;

        private CancellableBoundedInputStream(InputStream delegate, long maxBytes, BooleanSupplier cancelled) {
            super(delegate);
            this.maxBytes = maxBytes;
            this.cancelled = cancelled == null ? () -> false : cancelled;
        }

        @Override public int read() throws IOException {
            check();
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            check();
            long remaining = maxBytes - count;
            if (remaining <= 0) {
                int extra = super.read();
                if (extra < 0) return -1;
                count++;
                throw new IOException("Conversion input exceeds limit: " + maxBytes + " bytes");
            }
            int boundedLength = (int) Math.min(length, remaining);
            int read = super.read(buffer, offset, boundedLength);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(long delta) throws IOException {
            count += delta;
            if (count > maxBytes) throw new IOException("Conversion input exceeds limit: " + maxBytes + " bytes");
        }

        private void check() {
            if (cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
        }
    }

}
