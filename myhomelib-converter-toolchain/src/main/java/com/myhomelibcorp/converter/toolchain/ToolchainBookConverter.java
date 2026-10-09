package com.myhomelibcorp.converter.toolchain;

import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.epub.optimizer.EpubAcceptanceMode;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.epub.optimizer.EpubOptimizer;
import com.myhomelibcorp.epub.optimizer.profile.StandardReadingSystemProfile;
import com.myhomelibcorp.converter.toolchain.validation.ConversionOutputValidators;
import com.myhomelibcorp.converter.toolchain.metadata.CanonicalBookMetadata;
import com.myhomelibcorp.converter.toolchain.metadata.Fb2MetadataPatcher;
import com.myhomelibcorp.converter.toolchain.workspace.ConversionWorkspace;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * MyHomeLib-owned orchestration provider. Complex format engines remain isolated external tools;
 * this class owns route selection, staging, cancellation, validation and deterministic policy.
 */
@Component
public class ToolchainBookConverter implements BookConverter {
    private static final Logger log = LoggerFactory.getLogger(ToolchainBookConverter.class);
    private final Map<ConversionEngineId, ConversionEngine> engines;
    private final ConversionRoutePlanner planner;
    private static final String EPUB_OPTIMIZATION_PROFILE = "converter.epubOptimizationProfile";
    private static final String EPUB_READING_SYSTEM_PROFILE = "converter.epubReadingSystemProfile";
    private static final String EPUB_ACCEPTANCE_MODE = "converter.epubAcceptanceMode";

    private final ConversionOutputValidators validators;
    private final ApplicationSettingsPort settings;
    private final EpubOptimizer epubOptimizer;
    private final Fb2MetadataPatcher fb2MetadataPatcher;

    public ToolchainBookConverter(List<ConversionEngine> engines,
                                  ConversionOutputValidators validators,
                                  ApplicationSettingsPort settings,
                                  EpubOptimizer epubOptimizer,
                                  Fb2MetadataPatcher fb2MetadataPatcher) {
        EnumMap<ConversionEngineId, ConversionEngine> map = new EnumMap<>(ConversionEngineId.class);
        for (ConversionEngine engine : engines) {
            ConversionEngine previous = map.put(engine.id(), engine);
            if (previous != null) throw new IllegalStateException("Duplicate conversion engine: " + engine.id());
        }
        this.engines = Collections.unmodifiableMap(map);
        this.planner = new ConversionRoutePlanner(List.copyOf(engines));
        this.validators = validators;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.epubOptimizer = Objects.requireNonNull(epubOptimizer, "epubOptimizer");
        this.fb2MetadataPatcher = Objects.requireNonNull(fb2MetadataPatcher, "fb2MetadataPatcher");
    }

    @Override public String id() { return "myhomelib-toolchain"; }
    @Override public int priority() { return 500; }
    @Override public boolean isAvailable() { return !planner.availableEdges().isEmpty(); }

    @Override
    public boolean supports(Book book) {
        return supports(book, sourceFormat(book));
    }

    @Override
    public boolean supports(Book book, String sourceFormat) {
        String source = BookConversionCapability.normalizeFormat(sourceFormat);
        if (source.isBlank()) return false;
        return capabilities().stream().anyMatch(capability -> capability.supportsSource(source));
    }

    @Override public String getTargetExtension() { return ".epub"; }
    @Override public String getFormatName() { return "MyHomeLib Toolchain"; }

    @Override
    public Set<BookConversionCapability> capabilities() {
        List<ConversionStep> edges = planner.availableEdges();
        if (edges.isEmpty()) return Set.of();
        Set<String> sources = new TreeSet<>();
        Set<String> targets = new TreeSet<>();
        for (ConversionStep edge : edges) {
            sources.add(edge.sourceFormat());
            targets.add(edge.targetFormat());
        }
        LinkedHashSet<BookConversionCapability> result = new LinkedHashSet<>();
        for (String source : sources) {
            for (String target : targets) {
                planner.plan(source, target).ifPresent(route -> {
                    ConversionStep last = route.steps().get(route.steps().size() - 1);
                    result.add(new BookConversionCapability(Set.of(source), target, last.targetExtension()));
                });
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public void convert(BookConversionContext context) throws Exception {
        context.checkCancelled();
        ConversionRoute route = planner.plan(context.sourceFormat(), context.targetFormat())
                .orElseThrow(() -> new ConversionToolchainException(ConversionFailureKind.UNSUPPORTED,
                        "No toolchain route for " + context.sourceFormat() + " -> " + context.targetFormat()));
        log.info("CONVERSION_ROUTE bookId={} source={} target={} steps={}",
                context.book().getId(), context.sourceFormat(), context.targetFormat(),
                route.steps().stream().map(step -> step.engineId() + ":" + step.sourceFormat() + "->" + step.targetFormat()).toList());

        CanonicalBookMetadata metadataSnapshot = CanonicalBookMetadata.from(context.book());
        try (ConversionWorkspace workspace = ConversionWorkspace.create()) {
            Path source = workspace.sourcePath(extensionFor(context.sourceFormat()));
            copyInput(context, source);
            Path current = source;
            for (int i = 0; i < route.steps().size(); i++) {
                context.checkCancelled();
                ConversionStep step = route.steps().get(i);
                ConversionEngine engine = engines.get(step.engineId());
                if (engine == null || !engine.isAvailable()) {
                    throw new ConversionToolchainException(ConversionFailureKind.UNAVAILABLE,
                            "Required conversion engine is not available: " + step.engineId());
                }
                Path output = workspace.outputPath(i + 1, step.targetExtension());
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
                    throw new java.nio.file.FileAlreadyExistsException(output.toString());
                long stepStarted = System.nanoTime();
                log.info("CONVERSION_STEP_START bookId={} engine={} source={} target={}",
                        context.book().getId(), step.engineId(), step.sourceFormat(), step.targetFormat());
                engine.convert(step, current, output, context.cancelled(), context.maxOutputBytes());
                log.info("CONVERSION_STEP_DONE bookId={} engine={} durationMs={} outputBytes={}",
                        context.book().getId(), step.engineId(),
                        Math.max(0L, (System.nanoTime() - stepStarted) / 1_000_000L),
                        Files.size(output));
                if (step.engineId() == ConversionEngineId.PANDOC
                        && "fb2".equals(BookConversionCapability.normalizeFormat(step.targetFormat()))) {
                    fb2MetadataPatcher.patchMissing(output, metadataSnapshot, context.cancelled());
                }
                validators.forFormat(step.targetFormat()).validate(output, context.maxOutputBytes());
                current = output;
            }
            context.checkCancelled();
            if (context.postProcessEpub() && "epub".equals(BookConversionCapability.normalizeFormat(context.targetFormat()))) {
                long optimizeStarted = System.nanoTime();
                log.info("CONVERSION_EPUB_POST_START bookId={}", context.book().getId());
                Path optimized = workspace.outputPath(99, ".optimized.epub");
                if (Files.exists(optimized, LinkOption.NOFOLLOW_LINKS))
                    throw new java.nio.file.FileAlreadyExistsException(optimized.toString());
                try {
                    var optimizationReport = epubOptimizer.optimize(current, optimized, epubOptimizationProfile(),
                            epubReadingSystemProfile(), epubAcceptanceMode());
                    log.info("CONVERSION_EPUB_POST_REPORT bookId={} profile={} sourceBytes={} resultBytes={} "
                                    + "savedBytes={} savedPercent={} removedOrphans={} deduplicated={} optimizedImages={} "
                                    + "subsetFonts={} imagesBeforeBytes={} imagesAfterBytes={} markupBeforeBytes={} "
                                    + "markupAfterBytes={} epubCheck={} readerReopen={} semantic={} warnings={}",
                            context.book().getId(), optimizationReport.profile(), optimizationReport.sourceSize(),
                            optimizationReport.resultSize(), optimizationReport.savedBytes(),
                            String.format(java.util.Locale.ROOT, "%.2f", optimizationReport.savedPercent()),
                            optimizationReport.removedOrphans(), optimizationReport.deduplicatedResources(),
                            optimizationReport.imagesConverted(), optimizationReport.fontsSubset(),
                            optimizationReport.imagesBeforeBytes(), optimizationReport.imagesAfterBytes(),
                            optimizationReport.markupBeforeBytes(), optimizationReport.markupAfterBytes(),
                            optimizationReport.epubCheckStatus(), optimizationReport.readerReopenStatus(),
                            optimizationReport.semanticVerificationStatus(), optimizationReport.warnings().size());
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (ConversionToolchainException classified) {
                    throw classified;
                } catch (Exception invalidEpub) {
                    throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                            "EPUB post-processing or acceptance validation failed", invalidEpub);
                }
                validators.forFormat("epub").validate(optimized, context.maxOutputBytes());
                log.info("CONVERSION_EPUB_POST_DONE bookId={} durationMs={} outputBytes={}",
                        context.book().getId(), Math.max(0L, (System.nanoTime() - optimizeStarted) / 1_000_000L),
                        Files.size(optimized));
                current = optimized;
            }
            publishValidated(current, context.targetFile(), context.targetFormat(), context.maxOutputBytes(), context.cancelled());
            context.checkCancelled();
        } catch (CancellationException e) {
            throw e;
        }
    }


    private void publishValidated(Path staged, Path requestedTarget, String targetFormat, long maxOutputBytes,
                                  java.util.function.BooleanSupplier cancelled) throws Exception {
        Path safeCurrent = ConversionPathSafety.requireSafeRegularFile(staged, "Conversion staged output");
        String expectedHash = sha256Cancellable(safeCurrent, cancelled);
        Path target = ConversionPathSafety.prepareTarget(requestedTarget, "Conversion target");
        Path parent = target.getParent();
        if (parent == null) throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                "Conversion target has no parent directory");
        Path publicationWorkspace = ConversionPathSafety.createPrivateWorkspace(parent,
                "." + target.getFileName() + ".mhl-publication-", "Conversion publication workspace");
        Path temp = publicationWorkspace.resolve("candidate.tmp");
        Path previous = null;
        boolean originalDetached = false;
        boolean published = false;
        boolean success = false;
        Throwable failure = null;
        try {
            ConversionPathSafety.prepareTarget(temp, "Conversion temporary target");
            copyCancellable(safeCurrent, temp, cancelled);
            Path safeTemp = ConversionPathSafety.requireSafeRegularFile(temp, "Conversion temporary target");
            if (Files.mismatch(safeCurrent, safeTemp) != -1L) {
                throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                        "Conversion temporary copy verification failed");
            }
            validators.forFormat(targetFormat).validate(safeTemp, maxOutputBytes);
            if (!expectedHash.equalsIgnoreCase(sha256Cancellable(safeTemp, cancelled))) {
                throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                        "Conversion temporary copy hash verification failed");
            }

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Path safeExisting = ConversionPathSafety.requireSafeRegularFile(target, "Existing conversion target");
                String existingSnapshotHash = sha256Cancellable(safeExisting, cancelled);
                previous = publicationWorkspace.resolve("previous-target.tmp");
                ConversionPathSafety.prepareTarget(previous, "Conversion previous-target backup");

                // Detach the exact current target without REPLACE_EXISTING. If it changed after the
                // snapshot, restore that detached file and fail rather than overwriting unknown bytes.
                Path detached = ConversionPathSafety.moveNoReplace(target, previous, "Conversion existing-target detach");
                originalDetached = true;
                String detachedHash = sha256Cancellable(detached, null);
                if (!existingSnapshotHash.equalsIgnoreCase(detachedHash)) {
                    try {
                        ConversionPathSafety.moveNoReplace(detached, target, "Conversion changed-target restore");
                        previous = null;
                        originalDetached = false;
                    } catch (Exception restoreFailure) {
                        throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                                "Conversion target changed during publication; detached bytes preserved at " + detached,
                                restoreFailure);
                    }
                    throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                            "Conversion target changed during publication");
                }
            }

            // Deliberately no ATOMIC_MOVE+REPLACE_EXISTING: Java permits implementation-specific
            // replacement semantics for atomic moves. A racing target must make publication fail closed.
            ConversionPathSafety.moveNoReplace(safeTemp, target, "Conversion publication");
            published = true;
            Path safeTarget = ConversionPathSafety.requireSafeRegularFile(target, "Published conversion target");
            if (!expectedHash.equalsIgnoreCase(sha256Cancellable(safeTarget, null))
                    || Files.mismatch(safeCurrent, safeTarget) != -1L) {
                throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                        "Published conversion target verification failed");
            }
            success = true;
        } catch (CancellationException caught) {
            failure = caught;
            throw caught;
        } catch (ConversionToolchainException caught) {
            failure = caught;
            throw caught;
        } catch (Exception caught) {
            ConversionToolchainException wrapped = new ConversionToolchainException(
                    ConversionFailureKind.IO_FAILURE, "Cannot publish conversion output", caught);
            failure = wrapped;
            throw wrapped;
        } finally {
            if (!success) {
                boolean targetClear = !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (published) targetClear = removePublishedTargetIfOwned(target, expectedHash, parent, failure);
                if (originalDetached && previous != null && Files.exists(previous, LinkOption.NOFOLLOW_LINKS) && targetClear) {
                    try {
                        ConversionPathSafety.moveNoReplace(previous, target, "Conversion previous-target restore");
                        previous = null;
                        originalDetached = false;
                    } catch (Exception restoreFailure) {
                        if (failure != null) failure.addSuppressed(restoreFailure);
                    }
                }
            }
            // The candidate and a successfully superseded original live only inside our private
            // publication workspace. If an original could not be restored, preserve that workspace
            // as recovery evidence instead of deleting user bytes.
            if (success || !originalDetached) {
                try { ConversionPathSafety.cleanupPrivateWorkspace(publicationWorkspace, "Conversion publication workspace cleanup"); }
                catch (Exception cleanupFailure) { if (failure != null) failure.addSuppressed(cleanupFailure); }
            }
        }
    }

    private boolean removePublishedTargetIfOwned(Path target, String expectedHash, Path parent, Throwable failure) {
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true;
            Path safeTarget = ConversionPathSafety.requireSafeRegularFile(target, "Conversion rollback target");
            if (!expectedHash.equalsIgnoreCase(sha256Cancellable(safeTarget, null))) return false;
            Path evidence = parent.resolve("." + target.getFileName() + ".mhl-rollback-" + UUID.randomUUID() + ".tmp");
            ConversionPathSafety.prepareTarget(evidence, "Conversion rollback evidence");
            Path detached = ConversionPathSafety.moveNoReplace(safeTarget, evidence, "Conversion rollback detach");
            if (!expectedHash.equalsIgnoreCase(sha256Cancellable(detached, null))) {
                try { ConversionPathSafety.moveNoReplace(detached, target, "Conversion rollback evidence restore"); }
                catch (Exception restoreFailure) { if (failure != null) failure.addSuppressed(restoreFailure); }
                return false;
            }
            Files.delete(detached);
            return !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception rollbackFailure) {
            if (failure != null) failure.addSuppressed(rollbackFailure);
            return false;
        }
    }

    private String sha256Cancellable(Path file, java.util.function.BooleanSupplier cancelled) throws Exception {
        Path safe = ConversionPathSafety.requireSafeRegularFile(file, "Conversion hash input");
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = in.read(buffer)) >= 0; ) {
                if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void copyCancellable(Path source, Path target, java.util.function.BooleanSupplier cancelled) throws Exception {
        Path safeSource = ConversionPathSafety.requireSafeRegularFile(source, "Conversion publication source");
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
        try (InputStream in = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
                if (read > 0) out.write(buffer, 0, read);
            }
            out.flush();
        }
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        String source = sourceFormat(book);
        String target = formatFromTarget(targetFile);
        if (source.isBlank() || target.isBlank()) {
            throw new ConversionToolchainException(ConversionFailureKind.UNSUPPORTED,
                    "Legacy conversion call requires recognizable source and target formats");
        }
        convert(new BookConversionContext(book, source, target, sourceStream, targetFile,
                () -> false, 1024L * 1024L * 1024L));
    }

    private void copyInput(BookConversionContext context, Path target) throws Exception {
        try {
            Path safeTarget = ConversionPathSafety.prepareTarget(target, "Conversion staged input");
            if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(safeTarget.toString());
            }
            try (OutputStream out = Files.newOutputStream(safeTarget, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1024 * 1024];
                InputStream in = context.sourceStream();
                for (int read; (read = in.read(buffer)) >= 0; ) {
                    context.checkCancelled();
                    if (read > 0) out.write(buffer, 0, read);
                }
                out.flush();
            }
            ConversionPathSafety.requireSafeRegularFile(safeTarget, "Conversion staged input");
            context.checkCancelled();
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                    "Cannot stage conversion input", e);
        }
    }


    private EpubOptimizationProfile epubOptimizationProfile() {
        return parseEnum(EpubOptimizationProfile.class,
                settings.get(EPUB_OPTIMIZATION_PROFILE, EpubOptimizationProfile.BALANCED_EPUB33.name()),
                EpubOptimizationProfile.BALANCED_EPUB33);
    }

    private StandardReadingSystemProfile epubReadingSystemProfile() {
        return parseEnum(StandardReadingSystemProfile.class,
                settings.get(EPUB_READING_SYSTEM_PROFILE, StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT.name()),
                StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT);
    }

    private EpubAcceptanceMode epubAcceptanceMode() {
        return parseEnum(EpubAcceptanceMode.class,
                settings.get(EPUB_ACCEPTANCE_MODE, EpubAcceptanceMode.STANDARD.name()),
                EpubAcceptanceMode.STANDARD);
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private String sourceFormat(Book book) {
        if (book == null) return "";
        String name = book.getArchiveEntry();
        if (name == null || name.isBlank()) name = book.getFileName();
        return formatFromName(name);
    }

    private String formatFromTarget(Path target) { return formatFromName(target == null ? null : target.getFileName().toString()); }

    private String formatFromName(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return "fb2_zip";
        if (lower.endsWith(".kepub.epub") || lower.endsWith(".kepub")) return "kepub";
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : BookConversionCapability.normalizeFormat(lower.substring(dot + 1));
    }

    private String extensionFor(String format) {
        String normalized = BookConversionCapability.normalizeFormat(format);
        return switch (normalized) {
            case "fb2_zip" -> ".fb2.zip";
            case "kepub" -> ".kepub";
            default -> "." + normalized.replaceAll("[^a-z0-9]", "");
        };
    }
}
