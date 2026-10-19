package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.conversion.CancellableBoundedInputStream;
import com.myhomelibcorp.application.conversion.ConverterSelectionPolicy;
import com.myhomelibcorp.application.conversion.ConversionFailureSignal;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.epub.optimizer.EpubAcceptanceMode;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationReport;
import com.myhomelibcorp.epub.optimizer.EpubOptimizer;
import com.myhomelibcorp.epub.optimizer.profile.StandardReadingSystemProfile;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.organizer.fs.LibraryOperationCrashManifest;
import com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace;
import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import com.myhomelibcorp.organizer.journal.*;
import com.myhomelibcorp.organizer.plan.*;
import com.myhomelibcorp.organizer.policy.OriginalRetentionPolicy;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;
import static com.myhomelibcorp.organizer.apply.OrganizerFileIntegritySupport.*;
import static com.myhomelibcorp.organizer.apply.OrganizerArchiveSupport.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Crash-safe Apply phase. No permanent deletion is performed in v1; destructive paths go to quarantine. */
@Service
public final class ApplyLibraryOrganizationUseCase {
    private static final Logger log = LoggerFactory.getLogger(ApplyLibraryOrganizationUseCase.class);
    private static final long MAX_OUTPUT_BYTES=4L*1024L*1024L*1024L;
    // Skipped items do not change any book bytes, so their journal transitions can be
    // recorded in bounded batches without deferring a filesystem commit checkpoint.
    private static final int SKIP_JOURNAL_BATCH_SIZE = 128;
    private final List<BookConverter> converters;
    private final OrganizationJournalPort journal;
    private final LibraryOperationCoordinator operations;
    private final List<OrganizerCatalogPort> catalogs;
    private final EpubOptimizer epubOptimizer;
    private final ApplicationSettingsPort settings;
    private final OrganizerOutputValidationPort outputValidation;
    private final CollectionLifecyclePort collectionLifecycle;
    private final OrganizerTargetPublisher targetPublisher = new OrganizerTargetPublisher();

    /** Backward-compatible constructor for collection-independent organizer tests/callers. */
    public ApplyLibraryOrganizationUseCase(List<BookConverter> converters, OrganizationJournalPort journal,
                                           LibraryOperationCoordinator operations, List<OrganizerCatalogPort> catalogs,
                                           EpubOptimizer epubOptimizer, ApplicationSettingsPort settings,
                                           OrganizerOutputValidationPort outputValidation) {
        this(converters, journal, operations, catalogs, epubOptimizer, settings, outputValidation, null);
    }

    @Autowired
    public ApplyLibraryOrganizationUseCase(List<BookConverter> converters,OrganizationJournalPort journal,
                                           LibraryOperationCoordinator operations,List<OrganizerCatalogPort> catalogs,
                                           EpubOptimizer epubOptimizer, ApplicationSettingsPort settings,
                                           OrganizerOutputValidationPort outputValidation,
                                           CollectionLifecyclePort collectionLifecycle) {
        this.converters=(converters==null?List.<BookConverter>of():converters).stream().filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(BookConverter::priority).reversed().thenComparing(BookConverter::id)).toList();
        this.journal=Objects.requireNonNull(journal,"journal");this.operations=Objects.requireNonNull(operations,"operations");
        this.catalogs=catalogs==null?List.of():List.copyOf(catalogs);
        this.epubOptimizer=Objects.requireNonNull(epubOptimizer,"epubOptimizer");
        this.settings=settings;
        this.outputValidation=Objects.requireNonNull(outputValidation,"outputValidation");
        this.collectionLifecycle=collectionLifecycle;
    }

    public ApplyResult apply(OrganizationPlan plan, BooleanSupplier cancelled) throws Exception {
        return apply(plan, cancelled, progress -> { });
    }

    public ApplyResult apply(OrganizationPlan plan,
                             BooleanSupplier cancelled,
                             Consumer<OrganizationApplyProgress> progress) throws Exception {
        Objects.requireNonNull(plan, "plan");
        if (!plan.executable()) throw new IllegalStateException("Organization plan contains blocking issues");

        // Acquire the lifecycle barrier before any preflight/journal/workspace side effect. The plan captures
        // the collection that was active when Analyze/Preview started; validating it while this lease is held
        // closes the queued-worker race where a SWITCH could otherwise redirect catalog writes to another DB.
        LibraryOperationCoordinator.Lease lease = operations.acquireDetached(LibraryOperationType.ORGANIZE);
        try (OrganizerIoStallMonitor ioMonitor = new OrganizerIoStallMonitor(plan.operationId(), log::warn)) {
            log.info("ORGANIZER_APPLY_START operationId={} actions={}", plan.operationId(), plan.actions().size());
            requireExpectedCollection(plan);
            ApplyResult result = applyUnderLease(plan, cancelled, progress, lease, ioMonitor);
            log.info("ORGANIZER_APPLY_DONE operationId={} cancelled={} committed={} failed={}",
                    plan.operationId(), result.cancelled(), result.committed(), result.failed());
            return result;
        } catch (CancellationException cancellation) {
            lease.markCancelled("Cancelled during organizer preparation");
            log.info("ORGANIZER_APPLY_CANCELLED operationId={} phase=preparation", plan.operationId());
            return new ApplyResult(plan.operationId(), 0, 0, 0, 0, 0, 0, true, 0, 0L, 0L, 0L, List.of());
        } catch (Exception failure) {
            log.error("ORGANIZER_APPLY_FAILED operationId=" + plan.operationId(), failure);
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private ApplyResult applyUnderLease(OrganizationPlan plan,
                                        BooleanSupplier cancelled,
                                        Consumer<OrganizationApplyProgress> progress,
                                        LibraryOperationCoordinator.Lease lease,
                                        OrganizerIoStallMonitor ioMonitor) throws Exception {
        BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
        Consumer<OrganizationApplyProgress> progressSink = progress == null ? ignored -> { } : progress;
        long preflightStarted = System.nanoTime();
        log.info("ORGANIZER_APPLY_PREFLIGHT_START actions={}", plan.actions().size());
        ioMonitor.mark(null, "PREFLIGHT_START");
        preflight(plan, cancellation, progressSink, ioMonitor);
        checkCancelled(cancellation);
        log.info("ORGANIZER_APPLY_PREFLIGHT_DONE actions={} durationMs={}", plan.actions().size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preflightStarted));
        ioMonitor.mark(null, "PREFLIGHT_DONE");
        emit(progressSink, OrganizationApplyProgress.preparing(plan.actions().size(), plan.actions().size(), null, OrganizationApplyProgress.Phase.JOURNAL));
        ioMonitor.mark(null, "JOURNAL_START");
        long journalStarted = System.nanoTime();
        var journalItems = plan.actions().stream()
                .map(item -> journalItem(plan.operationId(), item, OrganizationItemState.PLANNED, item.expectedSourceSha256(), null, null))
                .toList();
        journal.begin(new OrganizationJournalOperation(plan.operationId(), plan.policy().id(), plan.sourceRoot(),
                plan.destinationRoot(), OrganizationOperationState.PLANNED, Instant.now(), null, "",
                plan.expectedCollectionId()), journalItems);
        log.info("ORGANIZER_APPLY_JOURNAL_INITIALIZED actions={} durationMs={}", plan.actions().size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - journalStarted));
        int committed = 0;
        int converted = 0;
        int moved = 0;
        int skipped = 0;
        int conflicts = 0;
        int failed = 0;
        int processed = 0;
        List<String> warnings = new ArrayList<>();
        int optimizedEpubs = 0;
        long optimizationSourceBytes = 0L, optimizationResultBytes = 0L, optimizationSavedBytes = 0L;
        boolean wasCancelled = false;
        List<OrganizationPlanItem> pendingSkips = new ArrayList<>(SKIP_JOURNAL_BATCH_SIZE);
        ioMonitor.mark(null, "JOURNAL_DONE");
        emit(progressSink, OrganizationApplyProgress.preparing(plan.actions().size(), plan.actions().size(), null, OrganizationApplyProgress.Phase.RECOVERY));
        ioMonitor.mark(null, "RECOVERY_START");
        long recoveryStarted = System.nanoTime();
        LibraryOperationWorkspace workspace = LibraryOperationWorkspace.create(plan.operationId());
        LibraryOperationCrashManifest crashManifest = LibraryOperationCrashManifest.create(plan, workspace.manifestPath());
        log.info("ORGANIZER_APPLY_RECOVERY_INITIALIZED actions={} durationMs={}", plan.actions().size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recoveryStarted));
        ioMonitor.mark(null, "RECOVERY_DONE");
        emit(progressSink, new OrganizationApplyProgress(plan.actions().size(), 0, 0, 0, 0, 0, 0, 0, null));
        long applyItemsStarted = System.nanoTime();
        try (workspace) {
            journal.updateOperation(plan.operationId(), OrganizationOperationState.APPLYING, "");
            crashManifest.operationState(OrganizationOperationState.APPLYING, "");
            try (ParallelStaging staging = startParallelStaging(plan, workspace, crashManifest, cancellation)) {
                for (OrganizationPlanItem item : plan.actions()) {
                    long itemStarted = System.nanoTime();
                    ioMonitor.mark(item.itemId(), "ITEM_START");
                    log.info("ORGANIZER_APPLY_ITEM_START operationId={} itemId={} action={} item={} total={}",
                            plan.operationId(), item.itemId(), item.action(), processed + 1, plan.actions().size());
                    if (cancellation.getAsBoolean()) {
                        wasCancelled = true;
                        throw new CancellationException("Library organization cancelled");
                    }
                    emit(progressSink, new OrganizationApplyProgress(plan.actions().size(), processed, committed, converted, moved, skipped, conflicts, failed, item.sourcePath()));
                    PreparedJob preparedJob = null;
                    String attemptedSourceHash = "";
                    try {
                        staging.throwIfFailed();
                        preparedJob = staging.job(item.itemId());
                        attemptedSourceHash = preparedJob == null ? "" : preparedJob.sourceHash();
                        if (attemptedSourceHash.isBlank()) {
                            // SKIPPED actions do not inspect the bytes in Apply, so their
                            // journal entry must not claim a freshly verified source digest.
                            attemptedSourceHash = isNonMutatingAction(item.action())
                                    ? "" : sourceHashValidatedDuringPreflight(item);
                        }
                        switch (item.action()) {
                            case NOOP, KEEP_AS_IS, REVIEW_DUPLICATE, SKIP_UNSUPPORTED -> {
                                pendingSkips.add(item);
                                if (pendingSkips.size() >= SKIP_JOURNAL_BATCH_SIZE)
                                    flushSkippedItems(plan.operationId(), pendingSkips, crashManifest);
                                skipped++;
                            }
                            case DELETE_EXACT_DUPLICATE -> {
                                commitRelocatedOriginal(item, plan, crashManifest, attemptedSourceHash, true);
                                committed++; moved++;
                            }
                            case RENAME, MOVE, MOVE_AND_RENAME -> {
                                commitExisting(item, plan, workspace, crashManifest, attemptedSourceHash, cancellation, ioMonitor);
                                committed++; moved++;
                            }
                            case EXTRACT -> {
                                commitPreparedExtract(item, plan, crashManifest, staging.await(requirePrepared(preparedJob, item)));
                                committed++;
                            }
                            case OPTIMIZE, OPTIMIZE_AND_MOVE -> {
                                EpubOptimizationReport report = commitPreparedOptimized(item, plan, crashManifest,
                                        staging.await(requirePrepared(preparedJob, item)));
                                warnings.addAll(report.warnings());
                                optimizedEpubs++; optimizationSourceBytes += report.sourceSize(); optimizationResultBytes += report.resultSize(); optimizationSavedBytes += report.savedBytes();
                                committed++;
                                if (item.action() == OrganizationAction.OPTIMIZE_AND_MOVE) moved++;
                            }
                            case CONVERT, CONVERT_AND_MOVE -> {
                                EpubOptimizationReport report = commitPreparedConverted(item, plan, crashManifest,
                                        staging.await(requirePrepared(preparedJob, item)));
                                if (report != null) {
                                    warnings.addAll(report.warnings());
                                    optimizedEpubs++; optimizationSourceBytes += report.sourceSize(); optimizationResultBytes += report.resultSize(); optimizationSavedBytes += report.savedBytes();
                                }
                                committed++; converted++;
                                if (item.action() == OrganizationAction.CONVERT_AND_MOVE) moved++;
                            }
                            case ARCHIVE_ORIGINAL -> {
                                commitRelocatedOriginal(item, plan, crashManifest, attemptedSourceHash, false);
                                committed++; moved++;
                            }
                            case CONFLICT, ERROR -> throw new IllegalStateException("Blocking action reached Apply: " + item.action());
                        }
                        // Nothing is staged for non-mutating actions. Avoid three directory
                        // enumerations per skipped book (particularly expensive for large plans).
                        if (!isNonMutatingAction(item.action())) {
                            cleanupCommittedStaging(workspace, item, warnings);
                        }
                        processed++;
                        long itemDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - itemStarted);
                        if (itemDurationMs >= 2_000L) {
                            log.info("ORGANIZER_APPLY_SLOW_ITEM action={} durationMs={} processed={} total={}",
                                    item.action(), itemDurationMs, processed, plan.actions().size());
                        }
                        if (processed % 100 == 0 || processed == plan.actions().size()) {
                            log.info("ORGANIZER_APPLY_ITEMS_PROGRESS processed={} total={} elapsedMs={} committed={} skipped={}",
                                    processed, plan.actions().size(),
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - applyItemsStarted), committed, skipped);
                        }
                        emit(progressSink, new OrganizationApplyProgress(plan.actions().size(), processed, committed, converted, moved, skipped, conflicts, failed, item.sourcePath()));
                    } catch (CancellationException cancellationException) {
                        staging.abort();
                        throw cancellationException;
                    } catch (Exception failure) {
                        log.error("ORGANIZER_APPLY_ITEM_FAILED operationId=" + plan.operationId() + " itemId=" + item.itemId() + " action=" + item.action(), failure);
                        OrganizationPlanItem stagedFailureItem = staging.fatalPlanItem();
                        staging.abort();
                        if (isConflictFailure(failure)) conflicts++; else failed++;
                        processed++;
                        if (stagedFailureItem == null || stagedFailureItem.itemId().equals(item.itemId())) {
                            try { transition(crashManifest, item, failedSnapshot(plan.operationId(), item, attemptedSourceHash, failure)); }
                            catch (Exception journalFailure) { failure.addSuppressed(journalFailure); }
                        }
                        Path failedPath = stagedFailureItem == null ? item.sourcePath() : stagedFailureItem.sourcePath();
                        emit(progressSink, new OrganizationApplyProgress(plan.actions().size(), processed, committed, converted, moved, skipped, conflicts, failed, failedPath));
                        throw failure;
                    }
                }
            }
            // Completion is recorded only once all pending non-mutating states are durable.
            flushSkippedItems(plan.operationId(), pendingSkips, crashManifest);
            String completedSummary = summary(committed, converted, moved, skipped, conflicts, failed);
            journal.updateOperation(plan.operationId(), OrganizationOperationState.COMPLETED, completedSummary);
            crashManifest.operationState(OrganizationOperationState.COMPLETED, completedSummary);
            lease.markCompleted(completedSummary);
            workspace.cleanup();
        } catch (CancellationException cancelledFailure) {
            try { flushSkippedItems(plan.operationId(), pendingSkips, crashManifest); }
            catch (Exception flushFailure) { cancelledFailure.addSuppressed(flushFailure); }
            wasCancelled = true;
            lease.markCancelled("Library organization cancelled");
            String cancelledSummary = summary(committed, converted, moved, skipped, conflicts, failed);
            journal.updateOperation(plan.operationId(), OrganizationOperationState.CANCELLED, cancelledSummary);
            try { crashManifest.operationState(OrganizationOperationState.CANCELLED, cancelledSummary); }
            catch (Exception manifestFailure) { cancelledFailure.addSuppressed(manifestFailure); }
        } catch (Exception failure) {
            // Any unflushed SKIPPED items are still PLANNED, which is crash-safe because
            // none changed a book. Do not retry a failed batch here: doing so could
            // overwrite the FAILED state recorded for its current item.
            lease.markFailed(failure);
            String failedSummary = summary(committed, converted, moved, skipped, conflicts, conflicts > 0 ? failed : Math.max(1, failed));
            try { journal.updateOperation(plan.operationId(), OrganizationOperationState.FAILED, failedSummary); }
            catch (Exception journalFailure) { failure.addSuppressed(journalFailure); }
            try { crashManifest.operationState(OrganizationOperationState.FAILED, failedSummary + ": " + rootMessage(failure)); }
            catch (Exception manifestFailure) { failure.addSuppressed(manifestFailure); }
            throw failure;
        }
        emit(progressSink, new OrganizationApplyProgress(plan.actions().size(), processed, committed, converted, moved, skipped, conflicts, failed, null));
        return new ApplyResult(plan.operationId(), committed, converted, moved, skipped, conflicts, failed, wasCancelled, optimizedEpubs,
                optimizationSourceBytes, optimizationResultBytes, optimizationSavedBytes, warnings);
    }

    private void flushSkippedItems(UUID operationId, List<OrganizationPlanItem> pending,
                                   LibraryOperationCrashManifest manifest) throws Exception {
        if (pending.isEmpty()) return;
        // Database state remains authoritative; an interrupted manifest append will force
        // conservative recovery review instead of silently accepting an inconsistent state.
        List<OrganizationJournalItem> snapshots = pending.stream()
                .map(item -> journalItem(operationId, item, OrganizationItemState.SKIPPED, "", null, null))
                .toList();
        journal.updateItems(snapshots);
        manifest.skippedItems(pending);
        pending.clear();
    }

    private void requireExpectedCollection(OrganizationPlan plan) {
        String expected = plan.expectedCollectionId() == null ? "" : plan.expectedCollectionId().trim();
        if (expected.isEmpty()) return;
        if (collectionLifecycle == null) {
            throw new IllegalStateException("Cannot verify organizer collection identity");
        }
        var current = collectionLifecycle.getCurrentCollection();
        String actual = current == null || current.getId() == null ? "" : current.getId();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Active collection changed since organizer Analyze/Preview");
        }
    }

    private static void emit(Consumer<OrganizationApplyProgress> progress, OrganizationApplyProgress snapshot) {
        try {
            progress.accept(snapshot);
        } catch (RuntimeException ignored) {
            // UI/progress observers must not be able to break filesystem transaction semantics.
        }
    }

    /**
     * Starts bounded parallel preparation for CPU/process-heavy actions only. Journal/catalog/final filesystem
     * commits stay on the caller thread and therefore remain strictly ordered.
     */
    private ParallelStaging startParallelStaging(OrganizationPlan plan, LibraryOperationWorkspace workspace,
                                                  LibraryOperationCrashManifest manifest, BooleanSupplier cancelled) throws Exception {
        int parallelism = Math.max(1, Math.min(4, plan.policy().maxParallelConversions()));
        List<OrganizationPlanItem> candidates = plan.actions().stream()
                .filter(item -> isParallelStageAction(item.action()))
                .toList();
        if (candidates.isEmpty()) return new ParallelStaging();
        return new ParallelStaging(plan, workspace, manifest, cancelled, candidates, parallelism);
    }

    private boolean isParallelStageAction(OrganizationAction action) {
        return action == OrganizationAction.CONVERT || action == OrganizationAction.CONVERT_AND_MOVE
                || action == OrganizationAction.OPTIMIZE || action == OrganizationAction.OPTIMIZE_AND_MOVE
                || action == OrganizationAction.EXTRACT;
    }

    private PreparedJob requirePrepared(PreparedJob job, OrganizationPlanItem item) {
        if (job != null) return job;
        throw new IllegalStateException("Staging coordinator did not prepare " + item.action() + " for " + sourceLabel(item));
    }

    private PreparedArtifact prepareArtifact(OrganizationPlanItem item, OrganizationPlan plan,
                                             LibraryOperationWorkspace workspace, BooleanSupplier cancelled,
                                             String sourceHash) throws Exception {
        if (cancelled.getAsBoolean()) throw new CancellationException("Library organization cancelled");
        return switch (item.action()) {
            case EXTRACT -> prepareExtracted(item, plan, workspace, cancelled, sourceHash);
            case OPTIMIZE, OPTIMIZE_AND_MOVE -> prepareOptimized(item, plan, workspace, cancelled, sourceHash);
            case CONVERT, CONVERT_AND_MOVE -> prepareConverted(item, plan, workspace, cancelled, sourceHash);
            default -> throw new IllegalArgumentException("Action is not parallel-stageable: " + item.action());
        };
    }

    private PreparedArtifact prepareOptimized(OrganizationPlanItem item, OrganizationPlan plan,
                                               LibraryOperationWorkspace workspace, BooleanSupplier cancelled,
                                               String sourceHash) throws Exception {
        if (!"epub".equals(BookConversionCapability.normalizeFormat(item.sourceFormat()))
                || !"epub".equals(BookConversionCapability.normalizeFormat(item.targetFormat()))) {
            throw new IllegalStateException("EPUB optimization action requires EPUB source and target");
        }
        Path optimizationSource = sourceForPreparation(item, workspace, cancelled, sourceHash);
        Path optimized = workspace.staged(item.itemId(), "optimized", extension(item.targetPath()));
        EpubOptimizationReport report = epubOptimizer.optimize(optimizationSource, optimized,
                plan.policy().epubOptimizationProfile(), epubReadingSystemProfile(), epubAcceptanceMode());
        if (cancelled.getAsBoolean()) throw new CancellationException("Library organization cancelled");
        validatePreparedFile(optimized);
        ensureSourceUnchanged(item, sourceHash);
        return new PreparedArtifact(optimized, sourceHash, sha256(optimized), report);
    }

    private PreparedArtifact prepareExtracted(OrganizationPlanItem item, OrganizationPlan plan,
                                               LibraryOperationWorkspace workspace, BooleanSupplier cancelled,
                                               String sourceHash) throws Exception {
        if (!item.archiveEntry()) throw new IllegalStateException("EXTRACT requires an archive entry source");
        Path staged = workspace.staged(item.itemId(), "extracted", extension(item.targetPath()));
        extractArchiveEntry(item, staged, cancelled, sourceHash);
        validatePreparedFile(staged);
        if (plan.policy().validateConvertedFiles()) outputValidation.validate(staged, item.targetFormat(), MAX_OUTPUT_BYTES);
        ensureSourceUnchanged(item, sourceHash);
        return new PreparedArtifact(staged, sourceHash, sha256(staged), null);
    }

    private PreparedArtifact prepareConverted(OrganizationPlanItem item, OrganizationPlan plan,
                                               LibraryOperationWorkspace workspace, BooleanSupplier cancelled,
                                               String sourceHash) throws Exception {
        List<BookConverter> candidates = selectConverters(item.sourceFormat(), item.targetFormat());
        Path sourceForConversion = sourceForPreparation(item, workspace, cancelled, sourceHash);
        Path converted = null;
        Book book = transientBook(item, sourceForConversion);
        Exception lastFallbackFailure = null;
        int providerAttempt = 0;
        for (BookConverter converter : candidates) {
            if (cancelled.getAsBoolean()) throw new CancellationException("Library organization cancelled");
            Path candidate = workspace.staged(item.itemId(), "converted-" + (++providerAttempt), extension(item.targetPath()));
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException("Organizer conversion staging path is already occupied: " + candidate);
            }
            try (InputStream raw = Files.newInputStream(
                         OrganizerPathSafety.canonicalExistingFile(sourceForConversion, "Organizer conversion source"),
                         LinkOption.NOFOLLOW_LINKS);
                 InputStream in = new CancellableBoundedInputStream(
                         raw, ArchiveSafetyLimits.MAX_ENTRY_BYTES, cancelled)) {
                converter.convert(new BookConversionContext(book, item.sourceFormat(), item.targetFormat(), in, candidate,
                        cancelled, MAX_OUTPUT_BYTES, false));
                converted = candidate;
                break;
            } catch (Exception failure) {
                // Preserve a failed provider artifact as workspace evidence instead of deleting a path
                // that the provider may have replaced concurrently. The next provider gets a fresh path.
                if (mayTryNextProvider(failure)) {
                    lastFallbackFailure = failure;
                    continue;
                }
                throw failure;
            }
        }
        if (converted == null) {
            if (lastFallbackFailure != null) throw lastFallbackFailure;
            throw new IllegalStateException("No usable converter for " + item.sourceFormat() + " -> " + item.targetFormat());
        }
        if (cancelled.getAsBoolean()) throw new CancellationException("Library organization cancelled");
        validatePreparedFile(converted);
        if (plan.policy().validateConvertedFiles()) outputValidation.validate(converted, item.targetFormat(), MAX_OUTPUT_BYTES);

        Path staged = converted;
        EpubOptimizationReport report = null;
        if ("epub".equals(BookConversionCapability.normalizeFormat(item.targetFormat()))
                && plan.policy().epubOptimizationProfile() != EpubOptimizationProfile.NONE) {
            Path optimized = workspace.staged(item.itemId(), "optimized", extension(item.targetPath()));
            report = epubOptimizer.optimize(converted, optimized, plan.policy().epubOptimizationProfile(),
                    epubReadingSystemProfile(), epubAcceptanceMode());
            staged = optimized;
            validatePreparedFile(staged);
        }
        if (cancelled.getAsBoolean()) throw new CancellationException("Library organization cancelled");
        ensureSourceUnchanged(item, sourceHash);
        return new PreparedArtifact(staged, sourceHash, sha256(staged), report);
    }

    private void validatePreparedFile(Path staged) throws Exception {
        if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(staged))
            throw new IllegalStateException("Prepared staged artifact is missing or unsafe: " + staged);
        long size = Files.size(staged);
        if (size <= 0L) throw new IllegalStateException("Prepared staged artifact is empty: " + staged);
        if (size > MAX_OUTPUT_BYTES) throw new IllegalStateException("Prepared staged artifact exceeds output limit: " + size);
    }

    private void ensureSourceUnchanged(OrganizationPlanItem item, String expectedHash) throws Exception {
        Path source = item.sourcePath();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source))
            throw new IllegalStateException("Source changed or disappeared during staging: " + source);
        String current = sha256Source(item);
        if (!current.equals(expectedHash)) throw new IllegalStateException("Source changed during staging: " + sourceLabel(item));
    }

    private void commitPreparedExtract(OrganizationPlanItem item, OrganizationPlan plan,
                                       LibraryOperationCrashManifest manifest, PreparedArtifact prepared) throws Exception {
        ensureSourceUnchanged(item, prepared.sourceHash());
        transition(manifest, item, journalItem(plan.operationId(), item, OrganizationItemState.VALIDATED,
                prepared.sourceHash(), prepared.targetHash(), null));
        boolean targetCommitted = false;
        try {
            targetPublisher.publish(prepared.staged(), item.targetPath(), prepared.targetHash());
            targetCommitted = true;
            applyCatalog(item);
        } catch (Exception failure) {
            rollbackCatalog(item, failure);
            if (targetCommitted) {
                try { removeOwnedFileIfPresent(item.targetPath(), prepared.targetHash(), "Organizer failed publication rollback"); }
                catch (Exception cleanup) { recordRetainedTarget(failure, item.targetPath(), cleanup); }
            }
            throw failure;
        }
        transition(manifest, item, journalItem(plan.operationId(), item, OrganizationItemState.COMMITTED,
                prepared.sourceHash(), prepared.targetHash(), null));
    }

    private EpubOptimizationReport commitPreparedOptimized(OrganizationPlanItem item, OrganizationPlan plan,
                                                             LibraryOperationCrashManifest manifest,
                                                             PreparedArtifact prepared) throws Exception {
        ensureSourceUnchanged(item, prepared.sourceHash());
        Path backup = item.archiveEntry() ? null : backupOriginal(item.sourcePath(),
                plan.destinationRoot().resolve(".myhomelib-originals").resolve(plan.operationId().toString()),
                item.itemId(), prepared.sourceHash());
        transition(manifest, item, journalItem(plan.operationId(), item, OrganizationItemState.VALIDATED,
                prepared.sourceHash(), prepared.targetHash(), backup, prepared.optimizationReport()));
        boolean inPlace = !item.archiveEntry() && same(item.sourcePath(), item.targetPath());
        if (inPlace) {
            ensureSourceUnchanged(item, prepared.sourceHash());
            removeOwnedFile(item.sourcePath(), prepared.sourceHash(), "Organizer in-place original removal");
        }
        boolean targetCommitted = false;
        try {
            targetPublisher.publish(prepared.staged(), item.targetPath(), prepared.targetHash());
            targetCommitted = true;
            applyCatalog(item);
            if (!item.archiveEntry() && !inPlace) {
                ensureSourceUnchanged(item, prepared.sourceHash());
                removeOwnedFile(item.sourcePath(), prepared.sourceHash(), "Organizer source removal after optimized publish");
            }
        } catch (Exception failure) {
            rollbackCatalog(item, failure);
            if (targetCommitted) {
                try { removeOwnedFileIfPresent(item.targetPath(), prepared.targetHash(), "Organizer failed publication rollback"); }
                catch (Exception cleanup) { recordRetainedTarget(failure, item.targetPath(), cleanup); }
            }
            if (!item.archiveEntry())
                restoreRelocatedOriginal(backup, item.sourcePath(), prepared.sourceHash(), failure);
            throw failure;
        }
        transition(manifest, item, journalItem(plan.operationId(), item, OrganizationItemState.COMMITTED,
                prepared.sourceHash(), prepared.targetHash(), backup, prepared.optimizationReport()));
        return prepared.optimizationReport();
    }

    private EpubOptimizationReport commitPreparedConverted(OrganizationPlanItem item, OrganizationPlan plan,
                                                            LibraryOperationCrashManifest manifest,
                                                            PreparedArtifact prepared) throws Exception {
        ensureSourceUnchanged(item, prepared.sourceHash());
        Path originalBackup = null;
        if (!item.archiveEntry() && plan.policy().originalRetention() == OriginalRetentionPolicy.MOVE_ORIGINAL_TO_ARCHIVE) {
            originalBackup = backupOriginal(item.sourcePath(),
                    plan.destinationRoot().resolve(".myhomelib-originals").resolve(plan.operationId().toString()),
                    item.itemId(), prepared.sourceHash());
        } else if (!item.archiveEntry() && plan.policy().originalRetention() == OriginalRetentionPolicy.DELETE_ORIGINAL_AFTER_VERIFIED_CONVERSION) {
            originalBackup = backupOriginal(item.sourcePath(),
                    plan.destinationRoot().resolve(".myhomelib-trash").resolve(plan.operationId().toString()),
                    item.itemId(), prepared.sourceHash());
        }
        transition(manifest, item, journalItem(plan.operationId(), item, OrganizationItemState.VALIDATED,
                prepared.sourceHash(), prepared.targetHash(), originalBackup, prepared.optimizationReport()));

        boolean targetCommitted = false;
        try {
            targetPublisher.publish(prepared.staged(), item.targetPath(), prepared.targetHash());
            targetCommitted = true;
            applyCatalog(item);
            if (originalBackup != null) {
                ensureSourceUnchanged(item, prepared.sourceHash());
                removeOwnedFile(item.sourcePath(), prepared.sourceHash(), "Organizer source removal after converted publish");
            }
        } catch (Exception failure) {
            rollbackCatalog(item, failure);
            if (targetCommitted) {
                try { removeOwnedFileIfPresent(item.targetPath(), prepared.targetHash(), "Organizer failed publication rollback"); }
                catch (Exception cleanup) { recordRetainedTarget(failure, item.targetPath(), cleanup); }
            }
            if (originalBackup != null)
                restoreRelocatedOriginal(originalBackup, item.sourcePath(), prepared.sourceHash(), failure);
            throw failure;
        }
        transition(manifest, item, journalItem(plan.operationId(), item, OrganizationItemState.COMMITTED,
                prepared.sourceHash(), prepared.targetHash(), originalBackup, prepared.optimizationReport()));
        return prepared.optimizationReport();
    }

    private record PreparedArtifact(Path staged, String sourceHash, String targetHash,
                                    EpubOptimizationReport optimizationReport) { }

    private record PreparedJob(UUID itemId, String sourceHash, Future<PreparedArtifact> future) { }
    private record StageFailure(UUID itemId, String sourceHash, Throwable cause) { }

    private final class ParallelStaging implements AutoCloseable {
        private final ThreadPoolExecutor executor;
        private final AtomicBoolean abortRequested = new AtomicBoolean(false);
        private final AtomicBoolean fatalJournaled = new AtomicBoolean(false);
        private final AtomicReference<StageFailure> fatalFailure = new AtomicReference<>();
        private final Map<UUID, PreparedJob> jobs = new LinkedHashMap<>();
        private final List<OrganizationPlanItem> candidates;
        // Constant-time membership lookup: the previous linear scan ran once for
        // every skipped/moved item whenever the plan included staged work.
        private final Set<UUID> candidateIds;
        private final OrganizationPlan plan;
        private final LibraryOperationWorkspace workspace;
        private final LibraryOperationCrashManifest manifest;
        private final BooleanSupplier cancelled;
        private final boolean enabled;
        private int nextCandidate;
        private boolean closed;

        /** Disabled coordinator used for the sequential path. */
        private ParallelStaging() {
            this.executor = null;
            this.candidates = List.of();
            this.candidateIds = Set.of();
            this.plan = null;
            this.workspace = null;
            this.manifest = null;
            this.cancelled = () -> false;
            this.enabled = false;
        }

        private ParallelStaging(OrganizationPlan plan, LibraryOperationWorkspace workspace,
                                LibraryOperationCrashManifest manifest, BooleanSupplier cancelled,
                                List<OrganizationPlanItem> candidates, int parallelism) throws Exception {
            this.plan = plan;
            this.workspace = workspace;
            this.manifest = manifest;
            this.cancelled = cancelled;
            this.candidates = List.copyOf(candidates);
            Set<UUID> ids = new HashSet<>(this.candidates.size());
            for (OrganizationPlanItem candidate : this.candidates) {
                if (!ids.add(candidate.itemId())) {
                    throw new IllegalArgumentException("Duplicate staged organizer item ID: " + candidate.itemId());
                }
            }
            this.candidateIds = Set.copyOf(ids);
            this.enabled = true;
            this.executor = new ThreadPoolExecutor(
                    parallelism, parallelism, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(Math.max(1, parallelism)),
                    runnable -> {
                        Thread thread = new Thread(runnable, "myhomelib-organizer-stage");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            this.executor.allowCoreThreadTimeOut(true);
            try {
                for (int i = 0; i < parallelism && nextCandidate < this.candidates.size(); i++) submitNext();
            } catch (Exception failure) {
                abort();
                throw failure;
            }
        }

        PreparedJob job(UUID itemId) throws Exception {
            throwIfFailed();
            PreparedJob job = jobs.get(itemId);
            if (job == null && enabled && candidateIds.contains(itemId)) {
                throw new IllegalStateException("Parallel staging window lost plan order for item " + itemId);
            }
            return job;
        }

        PreparedArtifact await(PreparedJob job) throws Exception {
            if (job == null) throw new IllegalArgumentException("Prepared job is required");
            try {
                PreparedArtifact artifact;
                long lastWaitLog = System.nanoTime();
                while (true) {
                    throwIfFailed();
                    if (cancelled.getAsBoolean()) {
                        abort();
                        throw new CancellationException("Library organization cancelled during staging");
                    }
                    try {
                        artifact = job.future().get(200L, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException waiting) {
                        if (System.nanoTime() - lastWaitLog >= TimeUnit.SECONDS.toNanos(5)) {
                            log.info("ORGANIZER_STAGING_WAIT operationId={} itemId={}", plan.operationId(), job.itemId());
                            lastWaitLog = System.nanoTime();
                        }
                    }
                }
                jobs.remove(job.itemId());
                if (!closed && nextCandidate < candidates.size()) submitNext();
                return artifact;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                abort();
                throw new CancellationException("Parallel staging interrupted");
            } catch (CancellationException cancelledFailure) {
                throwIfFailed();
                throw cancelledFailure;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                registerFatal(job.itemId(), job.sourceHash(), cause);
                journalFatalIfNeeded();
                abort();
                throwFailure(cause);
                throw new IllegalStateException("Unreachable");
            }
        }

        private void submitNext() throws Exception {
            throwIfFailed();
            if (nextCandidate >= candidates.size()) return;
            if (cancelled.getAsBoolean()) throw new CancellationException("Library organization cancelled");
            OrganizationPlanItem item = candidates.get(nextCandidate++);
            String sourceHash = sourceHashValidatedDuringPreflight(item);
            transition(manifest, item, journalItem(plan.operationId(), item, OrganizationItemState.STAGING, sourceHash, null, null));
            BooleanSupplier taskCancellation = () -> abortRequested.get() || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
            Future<PreparedArtifact> future = executor.submit(() -> {
                try {
                    return prepareArtifact(item, plan, workspace, taskCancellation, sourceHash);
                } catch (Throwable failure) {
                    // A worker failure is operation-fatal. Publish it immediately so the caller can
                    // stop committing later items; sibling workers observe abortRequested cooperatively.
                    if (!(failure instanceof CancellationException) || (!cancelled.getAsBoolean() && !abortRequested.get())) {
                        registerFatal(item.itemId(), sourceHash, failure);
                    }
                    abortRequested.set(true);
                    if (failure instanceof Exception exception) throw exception;
                    if (failure instanceof Error error) throw error;
                    throw new IllegalStateException("Parallel staging failed", failure);
                }
            });
            jobs.put(item.itemId(), new PreparedJob(item.itemId(), sourceHash, future));
        }

        void throwIfFailed() throws Exception {
            StageFailure failure = fatalFailure.get();
            if (failure == null) return;
            journalFatalIfNeeded();
            abort();
            throwFailure(failure.cause());
        }

        OrganizationPlanItem fatalPlanItem() {
            StageFailure failure = fatalFailure.get();
            if (failure == null) return null;
            return candidates.stream().filter(candidate -> candidate.itemId().equals(failure.itemId())).findFirst().orElse(null);
        }

        private void registerFatal(UUID itemId, String sourceHash, Throwable failure) {
            if (failure != null) fatalFailure.compareAndSet(null, new StageFailure(itemId, sourceHash, failure));
        }

        private void journalFatalIfNeeded() throws Exception {
            StageFailure failure = fatalFailure.get();
            if (failure == null || !fatalJournaled.compareAndSet(false, true)) return;
            try {
                OrganizationPlanItem failedItem = candidates.stream()
                        .filter(candidate -> candidate.itemId().equals(failure.itemId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Parallel staging failure has no plan item: " + failure.itemId()));
                transition(manifest, failedItem, failedSnapshot(plan.operationId(), failedItem, failure.sourceHash(), failure.cause()));
            } catch (Exception journalFailure) {
                fatalJournaled.set(false);
                throw journalFailure;
            }
        }

        private void throwFailure(Throwable failure) throws Exception {
            if (failure instanceof CancellationException cancellationFailure) {
                if (cancelled.getAsBoolean()) throw cancellationFailure;
                throw new IllegalStateException("Parallel staging worker cancelled without a user cancellation", cancellationFailure);
            }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Parallel staging failed", failure);
        }

        void abort() {
            if (!enabled || closed) return;
            abortRequested.set(true);
            for (PreparedJob job : jobs.values()) job.future().cancel(true);
            executor.shutdownNow();
            awaitTerminationQuietly();
            closed = true;
        }

        @Override public void close() {
            if (!enabled || closed) return;
            if (jobs.isEmpty() && nextCandidate >= candidates.size() && fatalFailure.get() == null) {
                executor.shutdown();
            } else {
                abortRequested.set(true);
                for (PreparedJob job : jobs.values()) {
                    if (!job.future().isDone()) job.future().cancel(true);
                }
                executor.shutdownNow();
            }
            awaitTerminationQuietly();
            closed = true;
        }

        private void awaitTerminationQuietly() {
            boolean interrupted = false;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!executor.isTerminated() && System.nanoTime() < deadline) {
                    try {
                        if (executor.awaitTermination(200L, TimeUnit.MILLISECONDS)) break;
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                        executor.shutdownNow();
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void cleanupCommittedStaging(LibraryOperationWorkspace workspace, OrganizationPlanItem item, List<String> warnings) {
        try {
            workspace.cleanupItem(item.itemId());
        } catch (Exception cleanupFailure) {
            warnings.add("Не вдалося одразу очистити staging для " + item.sourcePath() + ": " + rootMessage(cleanupFailure));
        }
    }

    private void commitExisting(OrganizationPlanItem item,OrganizationPlan plan,LibraryOperationWorkspace workspace,LibraryOperationCrashManifest manifest,String sourceHash,BooleanSupplier cancelled,OrganizerIoStallMonitor ioMonitor)throws Exception {
        transition(manifest,item,journalItem(plan.operationId(),item,OrganizationItemState.STAGING,sourceHash,null,null));
        Path staged=workspace.staged(item.itemId(),extension(item.targetPath()));
        ioMonitor.mark(item.itemId(), "COPY_START");
        log.info("ORGANIZER_STAGE operationId={} itemId={} stage=COPY_START", plan.operationId(), item.itemId());
        long copyStarted = System.nanoTime();
        final long[] lastCopyLog = {copyStarted};
        final boolean[] firstChunkLogged = {false};
        try {
            copyAndVerify(item.sourcePath(), staged, sourceHash, cancelled, (stage, bytes) -> {
                ioMonitor.mark(item.itemId(), "COPY_" + stage, bytes);
                if ("BYTES".equals(stage)) {
                    long now = System.nanoTime();
                    if (!firstChunkLogged[0] || now - lastCopyLog[0] >= TimeUnit.SECONDS.toNanos(5)) {
                        log.info("ORGANIZER_COPY operationId={} itemId={} stage=BYTES bytesCopied={} elapsedMs={}",
                                plan.operationId(), item.itemId(), bytes,
                                TimeUnit.NANOSECONDS.toMillis(now - copyStarted));
                        firstChunkLogged[0] = true;
                        lastCopyLog[0] = now;
                    }
                } else {
                    log.info("ORGANIZER_COPY operationId={} itemId={} stage={} bytesCopied={} elapsedMs={}",
                            plan.operationId(), item.itemId(), stage, bytes,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - copyStarted));
                }
            });
        } catch (Exception copyFailure) {
            log.warn("ORGANIZER_COPY_FAILED operationId={} itemId={} elapsedMs={}",
                    plan.operationId(), item.itemId(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - copyStarted), copyFailure);
            throw copyFailure;
        }
        log.info("ORGANIZER_STAGE operationId={} itemId={} stage=COPY_VERIFIED", plan.operationId(), item.itemId());
        // copyAndVerify already validated the staged bytes against sourceHash; the publisher
        // verifies both the destination temporary copy and the final target before returning.
        transition(manifest,item,journalItem(plan.operationId(),item,OrganizationItemState.VALIDATED,sourceHash,sourceHash,null));
        checkCancelled(cancelled);
        ioMonitor.mark(item.itemId(), "PUBLISH_START");
        log.info("ORGANIZER_STAGE operationId={} itemId={} stage=PUBLISH_START", plan.operationId(), item.itemId());
        targetPublisher.publish(staged,item.targetPath(),sourceHash,cancelled);
        log.info("ORGANIZER_STAGE operationId={} itemId={} stage=PUBLISH_VERIFIED", plan.operationId(), item.itemId());
        String targetHash=sourceHash;
        try {
            ioMonitor.mark(item.itemId(), "CATALOG_START");
            log.info("ORGANIZER_STAGE operationId={} itemId={} stage=CATALOG_START", plan.operationId(), item.itemId());
            applyCatalog(item);
            log.info("ORGANIZER_STAGE operationId={} itemId={} stage=CATALOG_DONE", plan.operationId(), item.itemId());
            if(!same(item.sourcePath(),item.targetPath())) {
                ensureSourceUnchanged(item, sourceHash);
                ioMonitor.mark(item.itemId(), "SOURCE_REMOVE_START");
                log.info("ORGANIZER_STAGE operationId={} itemId={} stage=SOURCE_REMOVE_START", plan.operationId(), item.itemId());
                removeOwnedFile(item.sourcePath(), sourceHash, "Organizer source removal after move");
                log.info("ORGANIZER_STAGE operationId={} itemId={} stage=SOURCE_REMOVE_DONE", plan.operationId(), item.itemId());
            }
        } catch(Exception failure) {
            rollbackCatalog(item, failure);
            if(!same(item.sourcePath(),item.targetPath())) {
                if (matchesRequiredHash(item.sourcePath(), sourceHash)) {
                    try { removeOwnedFileIfPresent(item.targetPath(), sourceHash, "Organizer move rollback target"); }
                    catch (Exception cleanup) { recordRetainedTarget(failure, item.targetPath(), cleanup); }
                } else {
                    failure.addSuppressed(new IllegalStateException(
                            "Source is no longer the expected original; retained verified target for recovery: " + item.targetPath()));
                }
            }
            throw failure;
        }
        transition(manifest,item,journalItem(plan.operationId(),item,OrganizationItemState.COMMITTED,sourceHash,targetHash,null));
    }

    private void recordRetainedTarget(Exception failure, Path target, Exception cleanup) {
        failure.addSuppressed(new IllegalStateException(
                "Retained target for recovery because rollback ownership verification failed: " + target, cleanup));
    }

    private Path sourceForPreparation(OrganizationPlanItem item, LibraryOperationWorkspace workspace,
                                      BooleanSupplier cancelled, String expectedHash) throws Exception {
        ensureSourceUnchanged(item, expectedHash);
        if (!item.archiveEntry()) return item.sourcePath();
        Path extracted = workspace.input(item.itemId(), sourceEntryExtension(item.sourceEntry()));
        if (Files.exists(extracted, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isRegularFile(extracted, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(extracted)
                    && sha256(extracted).equals(expectedHash)) return extracted;
            throw new IllegalStateException("Organizer archive staging path is already occupied by unexpected content: " + extracted);
        }
        extractArchiveEntry(item, extracted, cancelled, expectedHash);
        return extracted;
    }

    private void extractArchiveEntry(OrganizationPlanItem item, Path target, BooleanSupplier cancelled,
                                     String expectedHash) throws Exception {
        if (!item.archiveEntry()) throw new IllegalArgumentException("Archive entry source is required");
        OrganizerPathSafety.createDirectoriesNoLinks(target.toAbsolutePath().normalize().getParent(), "Organizer archive extraction target");
        requireSafeRegularFile(item.sourcePath(), "Archive source is unsafe");
        try (ZipFile zip = SafeZipSupport.openDetected(item.sourcePath(), "Organizer source archive")) {
            ZipArchiveEntry selected = requireEntry(zip, item.sourceEntry());
            try (java.io.OutputStream out = OrganizerPathSafety.openFreshOutputStream(target, "Organizer archive extraction target")) {
                copyEntry(zip, selected, out, cancelled);
            }
            if (!sha256(target).equals(expectedHash)) throw new IllegalStateException("Archive entry hash changed during extraction");
        } finally {
            // Keep a failed/partial extraction inside the operation-private workspace as evidence.
            // Unconditional delete here could remove a path concurrently replaced after validation.
            // The workspace cleanup phase owns eventual removal after durable completion/rollback.
        }
    }

    private String sha256Source(OrganizationPlanItem item) throws Exception {
        return sha256Source(item, () -> false);
    }

    private String sha256Source(OrganizationPlanItem item, BooleanSupplier cancelled) throws Exception {
        return sha256Source(item, cancelled, bytes -> { });
    }

    private String sha256Source(OrganizationPlanItem item, BooleanSupplier cancelled,
                                java.util.function.LongConsumer byteProgress) throws Exception {
        requireSafeRegularFile(item.sourcePath(), "Organizer source is unsafe");
        return item.archiveEntry()
                ? OrganizerArchiveSupport.sha256(item.sourcePath(), item.sourceEntry(), cancelled)
                : sha256(item.sourcePath(), cancelled, byteProgress);
    }

    private long sourceSize(OrganizationPlanItem item) throws Exception {
        if (!item.archiveEntry()) {
            return Files.size(OrganizerPathSafety.canonicalExistingFile(item.sourcePath(), "Organizer source size"));
        }
        return OrganizerArchiveSupport.size(item.sourcePath(), item.sourceEntry());
    }

    private String sourceEntryExtension(String entry) {
        if (entry == null || entry.isBlank()) return ".bin";
        String normalized = entry.replace('\\','/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return ".bin";
        return name.substring(dot).replaceAll("[^A-Za-z0-9._-]", "");
    }

    private String sourceLabel(OrganizationPlanItem item) {
        return item.archiveEntry() ? item.sourcePath() + "!/" + item.sourceEntry() : item.sourcePath().toString();
    }

    private StandardReadingSystemProfile epubReadingSystemProfile(){
        String value=settings==null?StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT.name():settings.get("converter.epubReadingSystemProfile",StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT.name());
        try{return StandardReadingSystemProfile.valueOf(value.trim().toUpperCase(Locale.ROOT));}
        catch(Exception ignored){return StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT;}
    }

    private EpubAcceptanceMode epubAcceptanceMode(){
        String value=settings==null?EpubAcceptanceMode.STANDARD.name():settings.get("converter.epubAcceptanceMode",EpubAcceptanceMode.STANDARD.name());
        try{return EpubAcceptanceMode.valueOf(value.trim().toUpperCase(Locale.ROOT));}
        catch(Exception ignored){return EpubAcceptanceMode.STANDARD;}
    }

    private List<BookConverter> selectConverters(String source,String target){
        String s=BookConversionCapability.normalizeFormat(source),t=BookConversionCapability.normalizeFormat(target);
        String mode=settings==null?"AUTO":settings.get("converter.mode","AUTO");
        List<BookConverter> result=new ArrayList<>();
        for(BookConverter c:converters){
            if(!ConverterSelectionPolicy.allowed(c,mode)||!safeConverterAvailable(c)) continue;
            try {
                if(c.capabilities()!=null&&c.capabilities().stream().anyMatch(cap->cap!=null&&cap.supportsSource(s)&&cap.produces(t))) result.add(c);
            } catch(RuntimeException ignored) { }
        }
        if(!result.isEmpty()) return List.copyOf(result);
        throw new IllegalStateException("No available converter for "+s+" -> "+t+" in mode "+mode);
    }

    private boolean safeConverterAvailable(BookConverter converter){
        try{return converter!=null&&converter.isAvailable();}catch(RuntimeException ignored){return false;}
    }

    private static boolean mayTryNextProvider(Throwable failure){
        Throwable current=failure;int depth=0;
        while(current!=null&&depth++<16){
            if(current instanceof ConversionFailureSignal signal)return signal.mayTryNextProvider();
            current=current.getCause();
        }
        return false;
    }
    private Book transientBook(OrganizationPlanItem item, Path materialized)throws Exception{
        String display = item.archiveEntry() ? Path.of(item.sourceEntry().replace('\\','/')).getFileName().toString() : materialized.getFileName().toString();
        return Book.builder().title(fileStem(Path.of(display))).file(new BookFile(item.sourcePath().getFileName().toString(),
                item.sourcePath().getParent()==null?"":item.sourcePath().getParent().toString(),item.sourceEntry(),Files.size(materialized),"")).local(true).build();
    }
    private void applyCatalog(OrganizationPlanItem item)throws Exception{for(OrganizerCatalogPort c:catalogs)if(c.supports(item)){c.afterFilesystemCommit(item);return;}}
    private void rollbackCatalog(OrganizationPlanItem item,Exception original){
        for(OrganizerCatalogPort c:catalogs)if(c.supports(item)){try{c.rollbackCatalog(item);}catch(Exception rollback){original.addSuppressed(rollback);}return;}
    }
    private void restoreRelocatedOriginal(Path backup,Path source,String expected,Exception original){
        if(backup==null||source==null)return;
        try{
            if(Files.exists(source,LinkOption.NOFOLLOW_LINKS)){
                verifyRequiredHash(source,expected,"Rollback source already exists but is not the expected original");
                return;
            }
            Path parent=source.toAbsolutePath().normalize().getParent();
            if(parent==null)throw new IllegalStateException("Rollback source has no parent: "+source);
            OrganizerPathSafety.createDirectoriesNoLinks(parent,"Organizer rollback source directory");
            copyAndVerify(backup,source,expected);
        }catch(Exception restore){original.addSuppressed(restore);}
    }
    private void commitRelocatedOriginal(OrganizationPlanItem item, OrganizationPlan plan, LibraryOperationCrashManifest manifest,
                                         String sourceHash, boolean quarantine) throws Exception {
        transition(manifest,item,journalItem(plan.operationId(),item,OrganizationItemState.STAGING,sourceHash,null,null));
        Path root=plan.destinationRoot().resolve(quarantine?".myhomelib-trash":".myhomelib-originals").resolve(plan.operationId().toString());
        Path backup=backupOriginal(item.sourcePath(),root,item.itemId(),sourceHash);
        // backupOriginal verifies the backup content before returning.
        String backupHash=sourceHash;
        transition(manifest,item,journalItem(plan.operationId(),item,OrganizationItemState.VALIDATED,sourceHash,backupHash,backup));
        verifyRequiredHash(item.sourcePath(),sourceHash,"Source changed before relocation commit");
        removeOwnedFile(item.sourcePath(), sourceHash, "Organizer relocated-original source removal");
        try {
            applyCatalog(item);
        } catch(Exception failure) {
            rollbackCatalog(item,failure);
            restoreRelocatedOriginal(backup,item.sourcePath(),sourceHash,failure);
            throw failure;
        }
        transition(manifest,item,journalItem(plan.operationId(),item,OrganizationItemState.COMMITTED,sourceHash,backupHash,backup));
    }

    private Path backupOriginal(Path source,Path root,UUID itemId,String expectedHash)throws Exception{
        OrganizerPathSafety.createDirectoriesNoLinks(root,"Organizer recovery directory");
        Path target=root.resolve(itemId+"-"+source.getFileName());
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            verifyRequiredHash(source,expectedHash,"Source changed before recovery backup");
            verifyRequiredHash(target,expectedHash,"Existing recovery backup is unsafe or belongs to different content");
        }else{
            // copyAndVerify already checks the source before writing the recovery copy.
            copyAndVerify(source,target,expectedHash);
        }
        verifyRequiredHash(source,expectedHash,"Source changed while recovery backup was created");
        return target;
    }
    private void preflight(OrganizationPlan plan, BooleanSupplier cancelled, Consumer<OrganizationApplyProgress> progress,
                           OrganizerIoStallMonitor ioMonitor)throws Exception{
        checkCancelled(cancelled);
        int checked = 0;
        long lastLog = System.nanoTime();
        Set<Path> targets = new HashSet<>();
        List<Long> stagingEstimates = new ArrayList<>();
        long destinationNeed = 0L;
        long destinationTransientNeed = 0L;
        long largestSequentialStage = 0L;
        Path sourceRoot = OrganizerPathSafety.canonicalExistingDirectory(plan.sourceRoot(), "Organizer source root");
        Path destinationRoot = OrganizerPathSafety.canonicalForCreation(plan.destinationRoot(), "Organizer destination root");
        Path destinationExisting = OrganizerPathSafety.nearestExistingAncestor(destinationRoot);
        FileStore destinationStore = Files.getFileStore(destinationExisting);

        for (var item : plan.actions()) {
            emit(progress, OrganizationApplyProgress.preparing(plan.actions().size(), checked++, item.sourcePath(), OrganizationApplyProgress.Phase.PREFLIGHT));
            checkCancelled(cancelled);
            if (System.nanoTime() - lastLog >= TimeUnit.SECONDS.toNanos(5)) {
                log.info("ORGANIZER_PREFLIGHT_PROGRESS operationId={} checked={} total={}", plan.operationId(), checked - 1, plan.actions().size());
                lastLog = System.nanoTime();
            }
            Path canonicalSource = OrganizerPathSafety.canonicalExistingFile(item.sourcePath(), "Organizer source file");
            OrganizerPathSafety.requireUnderRoot(sourceRoot, canonicalSource, "Organizer source file");
            if (isNonMutatingAction(item.action())) {
                // No bytes are read or changed for these actions. Preserve path safety, but
                // avoid hashing entire libraries solely to record a SKIPPED journal state.
                continue;
            }
            // Validate managed-catalog updates BEFORE staging, journal creation or file publication.
            // An adapter that cannot track a move must reject it rather than allowing
            // the filesystem to diverge silently from the SQLite catalog.
            for (OrganizerCatalogPort catalog : catalogs) {
                catalog.validateBeforeFilesystemCommit(item);
            }
            // During the first large book SHA pass there are no per-item commits yet.
            // Emit a durable milestone before potentially slow disk I/O so an operator
            // can tell preflight hashing from an actual copy-stage stall.
            boolean diagnosticCheckpoint = checked == 1 || checked % 1000 == 0;
            if (diagnosticCheckpoint) {
                log.info("ORGANIZER_PREFLIGHT_SOURCE_START operationId={} item={} total={} action={}",
                        plan.operationId(), checked, plan.actions().size(), item.action());
            }
            ioMonitor.mark(item.itemId(), item.archiveEntry() ? "PREFLIGHT_ARCHIVE_HASH_START" : "PREFLIGHT_HASH_START");
            long hashStarted = System.nanoTime();
            final long[] lastHashLog = {hashStarted};
            final boolean[] firstHashLogged = {false};
            verifyExpectedSource(item, cancelled, bytes -> {
                ioMonitor.mark(item.itemId(), "PREFLIGHT_HASH_BYTES", bytes);
                long now = System.nanoTime();
                if (!firstHashLogged[0] || now - lastHashLog[0] >= TimeUnit.SECONDS.toNanos(5)) {
                    log.info("ORGANIZER_PREFLIGHT_HASH operationId={} itemId={} bytesHashed={} elapsedMs={}",
                            plan.operationId(), item.itemId(), bytes,
                            TimeUnit.NANOSECONDS.toMillis(now - hashStarted));
                    firstHashLogged[0] = true;
                    lastHashLog[0] = now;
                }
            });
            ioMonitor.mark(item.itemId(), "PREFLIGHT_HASH_DONE");
            long sourceBytes = sourceSize(item);
            if (diagnosticCheckpoint) {
                log.info("ORGANIZER_PREFLIGHT_SOURCE_VERIFIED operationId={} item={} sizeBytes={}",
                        plan.operationId(), checked, sourceBytes);
            }

            if (writesTarget(item.action()) && item.targetPath() != null) {
                Path target = item.targetPath().toAbsolutePath().normalize();
                OrganizerPathSafety.requireUnderRoot(destinationRoot, target, "Organizer target");
                OrganizerPathSafety.rejectLinkLikeExistingComponents(target.getParent(), "Organizer target path");
                if (!targets.add(target)) throw new IllegalStateException("Duplicate target in plan: " + target);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && (item.archiveEntry() || !same(target, item.sourcePath()))) {
                    throw new FileAlreadyExistsException(target.toString());
                }
            }

            long stageBytes = estimatedStagingBytes(item, sourceBytes);
            largestSequentialStage = Math.max(largestSequentialStage, stageBytes);
            if (isParallelStageAction(item.action())) stagingEstimates.add(stageBytes);
            destinationNeed = safeAdd(destinationNeed, estimatedDestinationGrowth(item, sourceBytes, destinationStore, plan.policy().originalRetention()));
            destinationTransientNeed = Math.max(destinationTransientNeed, estimatedDestinationTransient(item, sourceBytes, destinationStore));
        }

        emit(progress, OrganizationApplyProgress.preparing(plan.actions().size(), checked, null, OrganizationApplyProgress.Phase.PREFLIGHT));
        checkCancelled(cancelled);
        long reserve = 64L * 1024L * 1024L;
        int parallelism = Math.max(1, Math.min(4, plan.policy().maxParallelConversions()));
        stagingEstimates.sort(Comparator.reverseOrder());
        long parallelStageNeed = 0L;
        for (int i = 0; i < Math.min(parallelism, stagingEstimates.size()); i++) {
            parallelStageNeed = safeAdd(parallelStageNeed, stagingEstimates.get(i));
        }
        long workspaceNeed = Math.max(largestSequentialStage, parallelStageNeed);

        Path workspaceExisting = OrganizerPathSafety.nearestExistingAncestor(LibraryOperationWorkspace.baseRoot());
        OrganizerPathSafety.rejectLinkLikeExistingComponents(workspaceExisting, "Organizer staging workspace");
        FileStore workspaceStore = Files.getFileStore(workspaceExisting);
        long destinationRequired = safeAdd(reserve, safeAdd(destinationNeed, destinationTransientNeed));
        long workspaceRequired = safeAdd(reserve, workspaceNeed);
        if (sameFileStore(destinationStore, workspaceStore)) {
            long combined = safeAdd(reserve, safeAdd(safeAdd(destinationNeed, destinationTransientNeed), workspaceNeed));
            requireUsableSpace(destinationStore, combined, "destination/staging");
        } else {
            requireUsableSpace(destinationStore, destinationRequired, "destination");
            requireUsableSpace(workspaceStore, workspaceRequired, "staging workspace");
        }
    }

    private long estimatedStagingBytes(OrganizationPlanItem item, long sourceBytes) {
        long source = Math.max(1L, sourceBytes);
        return switch (item.action()) {
            case OPTIMIZE, OPTIMIZE_AND_MOVE -> safeMultiply(source, 5L);
            case CONVERT, CONVERT_AND_MOVE -> {
                long estimatedOutput = estimatedConversionOutput(source);
                boolean optimizeEpub = "epub".equals(BookConversionCapability.normalizeFormat(item.targetFormat()));
                yield optimizeEpub ? safeMultiply(estimatedOutput, 6L) : safeMultiply(estimatedOutput, 2L);
            }
            case EXTRACT -> source;
            case RENAME, MOVE, MOVE_AND_RENAME -> source;
            default -> 0L;
        };
    }

    private long estimatedDestinationGrowth(OrganizationPlanItem item, long sourceBytes, FileStore destinationStore,
                                            OriginalRetentionPolicy retention) throws Exception {
        long source = Math.max(1L, sourceBytes);
        return switch (item.action()) {
            case EXTRACT -> source;
            case OPTIMIZE, OPTIMIZE_AND_MOVE -> item.archiveEntry() ? source : safeMultiply(source, 2L); // target + optional durable Undo copy
            case CONVERT, CONVERT_AND_MOVE -> {
                long output = estimatedConversionOutput(source);
                boolean keepsRecoveryCopy = !item.archiveEntry() && retention != OriginalRetentionPolicy.KEEP_ORIGINAL;
                yield safeAdd(output, keepsRecoveryCopy ? source : 0L);
            }
            case DELETE_EXACT_DUPLICATE, ARCHIVE_ORIGINAL, RENAME, MOVE, MOVE_AND_RENAME ->
                    sameFileStore(Files.getFileStore(item.sourcePath()), destinationStore) ? 0L : source;
            default -> 0L;
        };
    }

    private long estimatedDestinationTransient(OrganizationPlanItem item, long sourceBytes, FileStore destinationStore) throws Exception {
        long source = Math.max(1L, sourceBytes);
        return switch (item.action()) {
            // These actions copy first and remove the source only after verification/catalog commit.
            case DELETE_EXACT_DUPLICATE, ARCHIVE_ORIGINAL, RENAME, MOVE, MOVE_AND_RENAME ->
                    sameFileStore(Files.getFileStore(item.sourcePath()), destinationStore) ? source : 0L;
            default -> 0L;
        };
    }

    private long estimatedConversionOutput(long sourceBytes) {
        long source = Math.max(1L, sourceBytes);
        // Planning estimate, not an acceptance limit: allow roughly 3x source plus container/style overhead.
        // The hard runtime output ceiling remains MAX_OUTPUT_BYTES.
        return Math.min(MAX_OUTPUT_BYTES, safeAdd(safeMultiply(source, 3L), 8L * 1024L * 1024L));
    }

    private long safeMultiply(long value, long factor) {
        if (value <= 0L || factor <= 0L) return 0L;
        if (value > Long.MAX_VALUE / factor) return Long.MAX_VALUE;
        return value * factor;
    }

    private long safeAdd(long left, long right) {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private boolean sameFileStore(FileStore left, FileStore right) {
        if (left == null || right == null) return false;
        if (left.equals(right)) return true;
        return Objects.equals(left.name(), right.name()) && Objects.equals(left.type(), right.type());
    }

    private void requireUsableSpace(FileStore store, long required, String purpose) throws Exception {
        long usable = store.getUsableSpace();
        if (usable < required) {
            throw new IllegalStateException("Insufficient free space for " + purpose + ": required " + required
                    + " bytes, available " + usable + " bytes");
        }
    }
    private boolean isNonMutatingAction(OrganizationAction action) {
        return action == OrganizationAction.NOOP || action == OrganizationAction.KEEP_AS_IS
                || action == OrganizationAction.REVIEW_DUPLICATE || action == OrganizationAction.SKIP_UNSUPPORTED;
    }
    private boolean writesTarget(OrganizationAction action){return action==OrganizationAction.RENAME||action==OrganizationAction.MOVE||action==OrganizationAction.MOVE_AND_RENAME||action==OrganizationAction.EXTRACT||action==OrganizationAction.OPTIMIZE||action==OrganizationAction.OPTIMIZE_AND_MOVE||action==OrganizationAction.CONVERT||action==OrganizationAction.CONVERT_AND_MOVE;}
    private void transition(LibraryOperationCrashManifest manifest, OrganizationPlanItem item, OrganizationJournalItem snapshot) throws Exception {
        journal.updateItem(snapshot);
        manifest.itemState(item, snapshot.state(), snapshot.sourceSha256(), snapshot.targetSha256(), snapshot.backupPath(), snapshot.errorMessage());
    }

    private OrganizationJournalItem failedSnapshot(UUID operationId, OrganizationPlanItem item, String sourceHash, Throwable failure) {
        OrganizationJournalItem previous = journal.items(operationId).stream()
                .filter(existing -> existing.itemId().equals(item.itemId()))
                .findFirst().orElse(null);
        if (previous == null) return journalItem(operationId, item, OrganizationItemState.FAILED, sourceHash, null, null, "APPLY_FAILED", rootMessage(failure));
        return previous.withOutcome(OrganizationItemState.FAILED, sourceHash,
                "APPLY_FAILED", rootMessage(failure));
    }

    private OrganizationJournalItem journalItem(UUID op,OrganizationPlanItem i,OrganizationItemState s,String sourceHash,String targetHash,Path backup){return journalItem(op,i,s,sourceHash,targetHash,backup,null,"","");}
    private OrganizationJournalItem journalItem(UUID op,OrganizationPlanItem i,OrganizationItemState s,String sourceHash,String targetHash,Path backup,EpubOptimizationReport report){return journalItem(op,i,s,sourceHash,targetHash,backup,report,"","");}
    private OrganizationJournalItem journalItem(UUID op,OrganizationPlanItem i,OrganizationItemState s,String sourceHash,String targetHash,Path backup,String code,String message){return journalItem(op,i,s,sourceHash,targetHash,backup,null,code,message);}
    private OrganizationJournalItem journalItem(UUID op,OrganizationPlanItem i,OrganizationItemState s,String sourceHash,String targetHash,Path backup,EpubOptimizationReport report,String code,String message){
        String profile=report==null?"":report.profile().name(); long before=report==null?0:report.sourceSize(); long after=report==null?0:report.resultSize();
        long saved=report==null?0:report.savedBytes(); double percent=report==null?0:report.savedPercent(); String summary=optimizationSummary(report);
        String targetStandard=report==null||report.targetStandard()==null?"":report.targetStandard().name();
        String deviceProfile=report==null?"":report.readingSystemProfile();
        String compressionBackend=report==null||report.compressionBackend()==null?"":report.compressionBackend().name();
        String validationStatus=report==null?"":report.semanticVerificationStatus();
        return new OrganizationJournalItem(op,i.itemId(),i.sourcePath(),i.sourceEntry(),i.targetPath(),sourceHash==null?"":sourceHash,targetHash==null?"":targetHash,i.action().name(),s,backup,profile,before,after,saved,percent,summary,targetStandard,deviceProfile,compressionBackend,validationStatus,code,message);
    }
    private String optimizationSummary(EpubOptimizationReport report){
        if(report==null)return "{}";
        List<String>types=new ArrayList<>();
        if(report.imagesBeforeBytes()!=report.imagesAfterBytes()||report.imagesConverted()>0||report.imagesResized()>0)types.add("images");
        if(report.fontsBeforeBytes()!=report.fontsAfterBytes()||report.fontsSubset()>0)types.add("fonts");
        if(report.markupBeforeBytes()!=report.markupAfterBytes())types.add("markup");
        if(report.zipBeforeBytes()!=report.zipAfterBytes())types.add("container");
        return "{\"semantic\":\""+json(report.semanticVerificationStatus())+"\",\"epubCheck\":\""+json(report.epubCheckStatus())+"\",\"readerReopen\":\""+json(report.readerReopenStatus())+"\",\"resourceTypes\":["+types.stream().map(v->"\""+json(v)+"\"").collect(java.util.stream.Collectors.joining(","))+"]}";
    }
    private String json(String value){return value==null?"":value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private String itemSourceHash(OrganizationPlanItem i)throws Exception{
        return itemSourceHash(i, () -> false);
    }
    private String itemSourceHash(OrganizationPlanItem i, BooleanSupplier cancelled)throws Exception{
        String actual=sha256Source(i, cancelled);
        String expected=i.expectedSourceSha256();
        if(expected!=null&&!expected.isBlank()&&!expected.equalsIgnoreCase(actual))
            throw new IllegalStateException("Source changed since Analyze/Preview: "+sourceLabel(i));
        return actual;
    }
    private void verifyExpectedSource(OrganizationPlanItem i, BooleanSupplier cancelled,
                                      java.util.function.LongConsumer byteProgress)throws Exception{
        String actual = sha256Source(i, cancelled, byteProgress);
        String expected = i.expectedSourceSha256();
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(actual))
            throw new IllegalStateException("Source changed since Analyze/Preview: " + sourceLabel(i));
    }
    /**
     * Preflight has already compared the entire source with the Analyze/Preview fingerprint.
     * Subsequent file-changing actions verify the bytes again at their commit boundary;
     * do not hash every source a redundant time merely to fill out a journal field.
     */
    private String sourceHashValidatedDuringPreflight(OrganizationPlanItem item) throws Exception {
        String expected = item.expectedSourceSha256();
        return expected == null || expected.isBlank() ? itemSourceHash(item) : expected;
    }
    private boolean same(Path a,Path b){return a!=null&&b!=null&&a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());}
    private String extension(Path p) {
        String name = p.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".fb2.zip")) {
            return name.substring(name.length() - ".fb2.zip".length());
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
    private String fileStem(Path p) {
        String name = p.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".fb2.zip")) {
            return name.substring(0, name.length() - ".fb2.zip".length());
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
    private boolean isConflictFailure(Throwable failure){
        Throwable current=failure;while(current!=null){if(current instanceof FileAlreadyExistsException)return true;current=current.getCause();}return false;
    }
    private String summary(int c,int converted,int moved,int s,int conflicts,int f){return "{\"committed\":"+c+",\"converted\":"+converted+",\"moved\":"+moved+",\"skipped\":"+s+",\"conflicts\":"+conflicts+",\"failed\":"+f+"}";}
    private String rootMessage(Throwable t){Throwable c=t;while(c!=null&&c.getCause()!=null)c=c.getCause();return c==null?"unknown":String.valueOf(c.getMessage());}
}
