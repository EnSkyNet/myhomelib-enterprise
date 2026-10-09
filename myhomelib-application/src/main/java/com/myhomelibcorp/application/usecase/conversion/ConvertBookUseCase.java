package com.myhomelibcorp.application.usecase.conversion;

import com.myhomelibcorp.shared.util.SafeFileNameSupport;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.conversion.CancellableBoundedInputStream;
import com.myhomelibcorp.application.conversion.ConverterSelectionPolicy;
import com.myhomelibcorp.application.conversion.ConversionFailureSignal;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
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
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
@Slf4j
public class ConvertBookUseCase {

    public static final long DEFAULT_MAX_INPUT_BYTES = 512L * 1024L * 1024L;
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 1024L * 1024L * 1024L;

    private final BookQueryRepository books;
    private final List<BookConverter> converters;
    private final BookResourcePort resources;
    private final CommittedCatalogMutationService mutations;
    private final LibraryOperationCoordinator operationCoordinator;
    private final CollectionLifecyclePort collectionLifecycle;
    private RuntimeExtensionRegistry runtimeExtensions = new RuntimeExtensionRegistry();
    private ApplicationSettingsPort settings;

    /** Compatibility constructor for focused non-Spring tests and integrations. */
    public ConvertBookUseCase(BookQueryRepository books, List<BookConverter> converters,
                              BookResourcePort resources, CommittedCatalogMutationService mutations) {
        this(books, converters, resources, mutations, new LibraryOperationCoordinator(), null);
    }

    /** Compatibility constructor for focused tests/integrations that do not need collection identity checks. */
    public ConvertBookUseCase(BookQueryRepository books, List<BookConverter> converters,
                              BookResourcePort resources, CommittedCatalogMutationService mutations,
                              LibraryOperationCoordinator operationCoordinator) {
        this(books, converters, resources, mutations, operationCoordinator, null);
    }

    @Autowired
    public ConvertBookUseCase(BookQueryRepository books, List<BookConverter> converters,
                              BookResourcePort resources, CommittedCatalogMutationService mutations,
                              LibraryOperationCoordinator operationCoordinator,
                              CollectionLifecyclePort collectionLifecycle) {
        this.books = books;
        this.converters = converters == null ? List.of() : List.copyOf(converters);
        this.resources = resources;
        this.mutations = mutations;
        this.operationCoordinator = operationCoordinator;
        this.collectionLifecycle = collectionLifecycle;
    }

    @Autowired
    void setRuntimeExtensions(RuntimeExtensionRegistry runtimeExtensions) {
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    @Autowired
    void setApplicationSettings(ApplicationSettingsPort settings) {
        this.settings = settings;
    }

    /**
     * Conversion request intentionally has no "make preferred" switch. A generated representation
     * is always registered as secondary; changing the primary artifact is a separate explicit user action.
     */
    public record Request(BookId bookId,
                          String targetFormat,
                          Path outputDirectory,
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
            return new Request(bookId, targetFormat, outputDirectory,
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
        return capabilityMatrix(bookId, null);
    }

    /**
     * Collection-stable capability discovery for UI/async callers. The expected collection is
     * verified only after the QUERY barrier has been acquired, closing the check/use race.
     */
    public List<CapabilityView> capabilityMatrix(BookId bookId, String expectedCollectionId) {
        Objects.requireNonNull(bookId, "bookId");
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            assertExpectedCollection(expectedCollectionId);
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
            List<CapabilityView> result = List.copyOf(matrix);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    public Result execute(Request request) throws Exception {
        return execute(request, null);
    }

    /** Executes a conversion only if the captured collection is still active under the CONVERSION barrier. */
    public Result execute(Request request, String expectedCollectionId) throws Exception {
        long started = System.nanoTime();
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.CONVERSION);
        try {
            assertExpectedCollection(expectedCollectionId);
            Result result = executeLocked(request);
            lease.markCompleted("");
            return result;
        } catch (CancellationException cancelled) {
            log.info("CONVERSION_CANCEL bookId={} target={} durationMs={}",
                    request == null ? "" : request.bookId(), request == null ? "" : request.targetFormat(), elapsedMs(started));
            lease.markCancelled(cancelled.getMessage());
            throw cancelled;
        } catch (Exception failure) {
            log.error("CONVERSION_FAIL bookId={} target={} durationMs={} error={}",
                    request == null ? "" : request.bookId(), request == null ? "" : request.targetFormat(),
                    elapsedMs(started), compact(failure));
            lease.markFailed(failure);
            throw failure;
        } catch (Error failure) {
            log.error("CONVERSION_FAIL bookId={} target={} durationMs={} error={}",
                    request == null ? "" : request.bookId(), request == null ? "" : request.targetFormat(),
                    elapsedMs(started), compact(failure));
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private void assertExpectedCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return;
        if (collectionLifecycle == null) {
            throw new IllegalStateException("Collection lifecycle is unavailable for collection-scoped conversion");
        }
        var current = collectionLifecycle.getCurrentCollection();
        String currentId = current == null ? null : current.getId();
        if (!Objects.equals(expectedCollectionId, currentId)) {
            throw new IllegalStateException("Active collection changed during conversion request");
        }
    }

    private Result executeLocked(Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        long startedAt = System.nanoTime();
        checkCancelled(request.cancelled());

        Book book = books.findById(request.bookId())
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + request.bookId()));
        List<SelectedJob> candidates = selectJobs(book, request.targetFormat());
        log.info("CONVERSION_START title='{}' bookId={} target={} mode={} candidates={}",
                safeLog(book.getTitle()), book.getId(), request.targetFormat(), configuredMode(), describeCandidates(candidates));

        // The destination comes from an explicit DirectoryChooser selection, not from catalog metadata.
        // Resolve it once to its real directory so ordinary Windows junction/OneDrive-style paths do not
        // get rejected by the stricter no-link policy used for untrusted/catalog-controlled paths.
        Path root = SafeFilePublication.requireUserSelectedDirectory(
                request.outputDirectory(), "Conversion output directory");
        if (!Files.isWritable(root)) {
            throw new IOException("Conversion output directory is not writable: " + root);
        }

        // Converter staging must not live under the selected destination. External/user-selected roots can
        // contain legitimate junctions/reparse points, while converter workspaces require a strict no-link
        // boundary. Keep all intermediate files under the application-owned cache and publish only the final
        // verified artifact to the selected directory.
        Path stagingRoot = SafeFilePublication.createDirectoriesNoLinks(
                AppPaths.cacheDir().resolve("conversion-staging"), "Conversion staging root");

        SelectedJob successfulJob = null;
        Path staged = null;
        Path successfulWorkspace = null;
        Exception lastFallbackFailure = null;
        SelectedJob lastFallbackJob = null;
        for (SelectedJob candidate : candidates) {
            checkCancelled(request.cancelled());
            Source source = candidate.source();
            if (source.sizeBytes() > 0 && source.sizeBytes() > request.maxInputBytes()) {
                throw new IllegalStateException("Conversion input exceeds limit: " + source.sizeBytes()
                        + " > " + request.maxInputBytes());
            }

            String candidateExtension = safeExtension(candidate.converter().capability().targetExtension());
            Path candidateWorkspace = SafeFilePublication.createPrivateWorkspace(
                    stagingRoot, ".mhl-convert-work-", "Conversion staging workspace");
            Path candidateStaged = candidateWorkspace.resolve("output" + candidateExtension);
            boolean keepCandidate = false;
            try {
                InputStream raw;
                try {
                    raw = source.open();
                } catch (Exception sourceFailure) {
                    throw new BookConversionException(BookConversionException.Stage.SOURCE_READ,
                            candidate.converter().converter().id(), source.format(), request.targetFormat(), sourceFailure);
                }
                long providerStarted = System.nanoTime();
                String providerId = candidate.converter().converter().id();
                log.info("CONVERSION_PROVIDER_START title='{}' bookId={} provider={} source={} target={}",
                        safeLog(book.getTitle()), book.getId(), providerId, source.format(), request.targetFormat());
                try (raw;
                     InputStream bounded = new CancellableBoundedInputStream(raw, request.maxInputBytes(), request.cancelled())) {
                    BookConversionContext context = new BookConversionContext(
                            book, source.format(), request.targetFormat(), bounded, candidateStaged,
                            request.cancelled(), request.maxOutputBytes());
                    candidate.converter().converter().convert(context);
                    log.info("CONVERSION_PROVIDER_DONE title='{}' bookId={} provider={} durationMs={}",
                            safeLog(book.getTitle()), book.getId(), providerId, elapsedMs(providerStarted));
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (BookConversionException classified) {
                    throw classified;
                } catch (Exception failure) {
                    if (mayTryNextProvider(failure)) {
                        log.warn("CONVERSION_PROVIDER_FALLBACK title='{}' bookId={} provider={} durationMs={} error={}",
                                safeLog(book.getTitle()), book.getId(), providerId, elapsedMs(providerStarted), compact(failure));
                        lastFallbackFailure = failure;
                        lastFallbackJob = candidate;
                        continue;
                    }
                    throw new BookConversionException(BookConversionException.Stage.PROVIDER,
                            candidate.converter().converter().id(), source.format(), request.targetFormat(), failure);
                }

                checkCancelled(request.cancelled());
                // Empty/oversized/unsafe output is a hard stop. Provider-classified invalid output may
                // already have fallen through to another provider before reaching this boundary.
                try {
                    validateOutput(candidateStaged, request.maxOutputBytes());
                } catch (IOException invalidOutput) {
                    throw new BookConversionException(BookConversionException.Stage.OUTPUT_VALIDATION,
                            candidate.converter().converter().id(), source.format(), request.targetFormat(), invalidOutput);
                }
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
            if (lastFallbackFailure != null && lastFallbackJob != null) {
                throw new BookConversionException(BookConversionException.Stage.PROVIDER,
                        lastFallbackJob.converter().converter().id(), lastFallbackJob.source().format(),
                        request.targetFormat(), lastFallbackFailure);
            }
            throw new IllegalArgumentException("No usable converter for target " + request.targetFormat());
        }

        Source source = successfulJob.source();
        SelectedConverter selected = successfulJob.converter();
        String artifactId = conversionArtifactId(book, request.targetFormat());
        String extension = safeExtension(selected.capability().targetExtension());
        Path finalFile = resolveConversionTarget(root, safeBaseName(book.getTitle(), book.getId()), extension);
        String finalName = finalFile.getFileName().toString();

        boolean registered = false;
        boolean finalMoved = false;
        Exception publicationFailure = null;
        String stagedSha256 = null;
        try {
            SafeFilePublication.FileSnapshot stagedSnapshot =
                    SafeFilePublication.snapshot(staged, "Conversion staged output");
            stagedSha256 = stagedSnapshot.sha256();
            SafeFilePublication.FileSnapshot publishedSnapshot =
                    SafeFilePublication.publishFreshToUserSelectedPath(
                            staged, finalFile, "Conversion artifact publication");
            finalMoved = true;

            long size = publishedSnapshot.size();
            String sha256 = publishedSnapshot.sha256();
            if (size <= 0 || size > request.maxOutputBytes()) {
                throw new IOException("Published conversion artifact has invalid size: " + size);
            }
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

            try {
                // A conversion is an additional representation of the same logical book. Never let
                // publishing it rewrite the canonical/original catalogue location in books.*.
                // The original source path is provenance and must remain stable; users can still
                // open/select the generated artifact from book_artifacts.
                mutations.upsertArtifact(book.getId(), artifact, false);
                log.info("CONVERSION_CATALOG_LINK bookId={} artifactId={} sourceArtifactId={} preferred=false output='{}'",
                        book.getId(), artifact.getId(), source.artifactId(), finalFile);
            } catch (RuntimeException registrationFailure) {
                throw new BookConversionException(BookConversionException.Stage.CATALOG_REGISTRATION,
                        selected.converter().id(), source.format(), request.targetFormat(), registrationFailure);
            }
            registered = true;

            long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            log.info("CONVERSION_DONE title='{}' bookId={} provider={} source={} target={} durationMs={} output='{}' bytes={}",
                    safeLog(book.getTitle()), book.getId(), selected.converter().id(), source.format(),
                    request.targetFormat(), durationMs, finalFile, size);
            return new Result(selected.converter().id(), artifact, durationMs);
        } catch (BookConversionException failure) {
            publicationFailure = failure;
            throw failure;
        } catch (Exception failure) {
            BookConversionException classified = new BookConversionException(
                    BookConversionException.Stage.PUBLICATION, selected.converter().id(),
                    source.format(), request.targetFormat(), failure);
            publicationFailure = classified;
            throw classified;
        } finally {
            if (finalMoved && !registered && stagedSha256 != null) {
                try { SafeFilePublication.removeUserSelectedFileIfOwned(
                        finalFile, stagedSha256, "Conversion artifact rollback"); }
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
        if (!jobs.isEmpty()) {
            List<SelectedJob> selected = List.copyOf(jobs);
            if (preferFastFb2EpubRoute(targetFormat)) {
                List<SelectedJob> fast = selected.stream()
                        .filter(job -> isFb2Family(job.source().format()))
                        .filter(job -> {
                            BookConverter converter = job.converter().converter();
                            return converter.priority() >= 1000 || "myhomelib-toolchain".equals(converter.id());
                        })
                        .toList();
                if (!fast.isEmpty()) return List.copyOf(fast);
            }
            return selected;
        }
        String formats = sources.stream().map(Source::format).distinct().toList().toString();
        throw new IllegalArgumentException("No converter for sources " + formats + " -> " + targetFormat);
    }

    private static boolean mayTryNextProvider(Throwable failure) {
        return ConversionFailureSignal.mayTryNextProvider(failure);
    }

    private List<Source> sourceCandidates(Book book) {
        List<Source> result = new ArrayList<>();
        Optional<BookArtifact> preferred = book.getPreferredArtifact().filter(BookArtifact::isAvailable);

        // A converted artifact is a useful reading/export preference, but it must not silently
        // become the preferred *conversion source*.  Chaining conversions through a derived PDF
        // (FB2 -> PDF -> EPUB) loses structure and can force AUTO mode onto Calibre even though
        // the original FB2 has a native/toolchain route.  Prefer original/imported artifacts,
        // while still respecting the user's preferred artifact within the same provenance tier.
        List<BookArtifact> available = book.getArtifacts().stream()
                .filter(BookArtifact::isAvailable)
                .sorted(Comparator
                        .comparingInt((BookArtifact artifact) -> isDerivedConversionArtifact(artifact) ? 1 : 0)
                        .thenComparingInt(artifact -> preferred.isPresent()
                                && preferred.get().getId().equals(artifact.getId()) ? 0 : 1))
                .toList();
        for (BookArtifact artifact : available) result.add(sourceFromArtifact(artifact));

        if (result.isEmpty()) {
            String format = sourceFormat("", book.getFile());
            result.add(new Source(format, book.getFileSize(), null, () -> resources.readBookData(book)
                    .orElseThrow(() -> new IllegalArgumentException("Book source is not readable: " + book.getId()))));
        }
        return List.copyOf(result);
    }

    private static boolean isDerivedConversionArtifact(BookArtifact artifact) {
        if (artifact == null) return false;
        String sourceId = artifact.getSourceId();
        if (sourceId != null && sourceId.regionMatches(true, 0, "conversion:", 0, "conversion:".length())) {
            return true;
        }
        Map<String, String> metadata = artifact.getMetadata();
        return metadata != null && (metadata.containsKey("conversion.sourceFormat")
                || metadata.containsKey("conversion.provider")
                || metadata.containsKey("conversion.createdAt"));
    }

    /**
     * Keep one generated representation per logical book/target format. Re-running the same
     * conversion updates the existing conversion artifact instead of growing duplicate rows.
     * Existing random-id artifacts are reused for backwards compatibility; new books get a
     * deterministic id so the invariant also survives process restarts.
     */
    private static String conversionArtifactId(Book book, String targetFormat) {
        String normalizedTarget = BookConversionCapability.normalizeFormat(targetFormat);
        for (BookArtifact artifact : book.getArtifacts()) {
            if (artifact == null || !isDerivedConversionArtifact(artifact)) continue;
            if (normalizedTarget.equals(BookConversionCapability.normalizeFormat(artifact.getFormat()))) {
                return artifact.getId();
            }
        }
        String stableKey = book.getId().asString() + "\n" + normalizedTarget;
        return "conversion:" + UUID.nameUUIDFromBytes(stableKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private static Path resolveConversionTarget(Path root, String baseName, String extension) {
        Path desired = root.resolve(baseName + extension).normalize();
        if (!desired.startsWith(root)) throw new IllegalArgumentException("Unsafe conversion target path");
        if (!Files.exists(desired, LinkOption.NOFOLLOW_LINKS)) return desired;

        int counter = 2;
        while (counter > 0) {
            Path candidate = root.resolve(baseName + " (" + counter + ")" + extension).normalize();
            if (!candidate.startsWith(root)) throw new IllegalArgumentException("Unsafe conversion target path");
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
            counter++;
        }
        throw new IllegalStateException("Conversion filename collision counter exhausted");
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
        String mode = configuredMode();
        return runtimeExtensions.mergeBookConverters(converters).stream()
                .filter(Objects::nonNull)
                .filter(converter -> ConverterSelectionPolicy.allowed(converter, mode))
                .sorted(Comparator.comparingInt(BookConverter::priority).reversed()
                        .thenComparing(BookConverter::id, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private String configuredMode() {
        return settings == null ? "AUTO" : settings.get("converter.mode", "AUTO").trim().toUpperCase(Locale.ROOT);
    }

    /**
     * AUTO must not silently turn a bundled/native FB2 -> EPUB attempt into a 10-30 minute
     * Calibre job. If the fast toolchain route is available, a failure is reported immediately;
     * users can still choose CALIBRE mode explicitly or opt in to the legacy slow fallback.
     */
    private boolean preferFastFb2EpubRoute(String targetFormat) {
        if (!"AUTO".equals(configuredMode())) return false;
        if (!"epub".equals(BookConversionCapability.normalizeFormat(targetFormat))) return false;
        return settings == null || !settings.getBoolean("converter.auto.allowSlowFallback", false);
    }

    private static boolean isFb2Family(String sourceFormat) {
        String normalized = BookConversionCapability.normalizeFormat(sourceFormat);
        return "fb2".equals(normalized) || "fb2_zip".equals(normalized);
    }

    private static String describeCandidates(List<SelectedJob> jobs) {
        return jobs.stream().map(job -> job.converter().converter().id() + ":"
                + job.source().format() + "->" + job.converter().capability().targetFormat()).toList().toString();
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String compact(Throwable failure) {
        if (failure == null) return "";
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String safeLog(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private record SelectedConverter(BookConverter converter, BookConversionCapability capability) { }
    private record SelectedJob(Source source, SelectedConverter converter) { }

}
