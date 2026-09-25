package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.acceptance.EpubAcceptanceValidator;
import com.myhomelibcorp.epub.optimizer.graph.*;
import com.myhomelibcorp.epub.optimizer.packaging.*;
import com.myhomelibcorp.epub.optimizer.profile.EpubTargetStandard;
import com.myhomelibcorp.epub.optimizer.profile.ReadingSystemProfile;
import com.myhomelibcorp.epub.optimizer.profile.StandardReadingSystemProfile;
import com.myhomelibcorp.epub.optimizer.semantic.EpubSemanticVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

@Component
public class EpubOptimizer {
    private final EpubStructuralValidator validator;
    private final EpubResourceGraphAnalyzer graphAnalyzer;
    private final EpubPackager packager;
    private final EpubExactDuplicateDeduplicator duplicateDeduplicator;
    private final EpubResourcePruner resourcePruner;
    private final EpubSafeMarkupNormalizer markupNormalizer;
    private final EpubSemanticVerifier semanticVerifier;
    private final EpubPackageVersionInspector packageVersionInspector;
    private final EpubBinaryResourceOptimizer binaryResourceOptimizer;
    private final EpubPackageVersionPromoter packageVersionPromoter;
    private final List<EpubAcceptanceValidator> acceptanceValidators;

    @Autowired
    public EpubOptimizer(EpubStructuralValidator validator,
                         EpubResourceGraphAnalyzer graphAnalyzer,
                         EpubPackager packager,
                         EpubExactDuplicateDeduplicator duplicateDeduplicator,
                         EpubResourcePruner resourcePruner,
                         EpubSafeMarkupNormalizer markupNormalizer,
                         EpubSemanticVerifier semanticVerifier,
                         EpubPackageVersionInspector packageVersionInspector,
                         EpubBinaryResourceOptimizer binaryResourceOptimizer,
                         EpubPackageVersionPromoter packageVersionPromoter,
                         List<EpubAcceptanceValidator> acceptanceValidators) {
        this.validator = validator;
        this.graphAnalyzer = graphAnalyzer;
        this.packager = packager;
        this.duplicateDeduplicator = duplicateDeduplicator;
        this.resourcePruner = resourcePruner;
        this.markupNormalizer = markupNormalizer;
        this.semanticVerifier = semanticVerifier;
        this.packageVersionInspector = packageVersionInspector;
        this.binaryResourceOptimizer = binaryResourceOptimizer;
        this.packageVersionPromoter = packageVersionPromoter;
        this.acceptanceValidators = acceptanceValidators == null ? List.of() : List.copyOf(acceptanceValidators);
    }

    /** Backward-compatible constructor used by focused tests and non-Spring adapters. */
    public EpubOptimizer(EpubStructuralValidator validator,
                         EpubResourceGraphAnalyzer graphAnalyzer,
                         EpubPackager packager,
                         EpubExactDuplicateDeduplicator duplicateDeduplicator,
                         EpubResourcePruner resourcePruner,
                         EpubSafeMarkupNormalizer markupNormalizer,
                         EpubSemanticVerifier semanticVerifier,
                         EpubPackageVersionInspector packageVersionInspector,
                         EpubBinaryResourceOptimizer binaryResourceOptimizer,
                         List<EpubAcceptanceValidator> acceptanceValidators) {
        this(validator, graphAnalyzer, packager, duplicateDeduplicator, resourcePruner, markupNormalizer,
                semanticVerifier, packageVersionInspector, binaryResourceOptimizer,
                new EpubPackageVersionPromoter(new EpubResourceReplacementWriter()), acceptanceValidators);
    }

    public EpubOptimizationReport optimize(Path source, Path target, EpubOptimizationProfile profile) throws Exception {
        return optimize(source, target, profile, StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT, EpubAcceptanceMode.STANDARD);
    }

    public EpubOptimizationReport optimize(Path source, Path target, EpubOptimizationProfile profile,
                                           ReadingSystemProfile readingSystem) throws Exception {
        return optimize(source, target, profile, readingSystem, EpubAcceptanceMode.STANDARD);
    }

    public EpubOptimizationReport optimize(Path source, Path target, EpubOptimizationProfile profile,
                                           ReadingSystemProfile readingSystem, EpubAcceptanceMode acceptanceMode) throws Exception {
        profile = profile == null ? EpubOptimizationProfile.BALANCED_EPUB33 : profile;
        readingSystem = readingSystem == null ? StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT : readingSystem;
        acceptanceMode = acceptanceMode == null ? EpubAcceptanceMode.STANDARD : acceptanceMode;
        source = EpubPathSafety.requireSafeRegularFile(source, "EPUB optimizer source");
        Path normalizedSource = source;
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedTarget)) {
            throw new IllegalArgumentException("EPUB Optimizer requires a distinct staged target; in-place replacement belongs to the journal/rollback layer");
        }
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("Target EPUB path must not be a symbolic link");
        }
        if (profile == EpubOptimizationProfile.MODERN_EPUB34 && readingSystem.targetStandard() != EpubTargetStandard.EPUB_3_4) {
            throw new IllegalArgumentException("MODERN_EPUB34 requires an explicit EPUB 3.4 reading-system profile");
        }
        target = EpubPathSafety.prepareTarget(target, "EPUB optimizer target");
        Path targetParent = target.getParent();
        if (targetParent == null) throw new IllegalStateException("EPUB optimizer target has no parent directory");
        Path optimizerWorkspace = EpubPathSafety.createPrivateWorkspace(targetParent,
                "." + target.getFileName() + ".mhl-opt-", "EPUB optimizer workspace");
        Path stagedSource = optimizerWorkspace.resolve("source.epub");
        try {
            // Freeze the caller-controlled source into one private, no-link staging file before any
            // validator/analyzer reopens it. This prevents the multi-stage optimizer pipeline from
            // observing different path targets if the original library path is replaced mid-run.
            EpubPathSafety.copyFreshFile(source, stagedSource, "EPUB optimizer source staging");
            source = stagedSource;

            checkCancelled();
            validator.validate(source);
            checkCancelled();
            String inputPackageVersion = packageVersionInspector.packageVersion(source);
            String promotionWarning = null;
            if (profile == EpubOptimizationProfile.MODERN_EPUB34
                    && !packageVersionInspector.isEpub34(inputPackageVersion)) {
                if (!packageVersionInspector.isEpub3(inputPackageVersion)) {
                    throw new IllegalArgumentException("MODERN_EPUB34 can safely promote only an EPUB 3.x package; source declares "
                            + inputPackageVersion);
                }
                EpubResourceGraph inputGraph = graphAnalyzer.analyze(source);
                Path promoted = optimizerWorkspace.resolve("source-epub34.epub");
                packageVersionPromoter.promoteTo34(source, promoted, inputGraph.packagePath(), optimizerWorkspace);
                checkCancelled();
                validator.validate(promoted);
                String promotedVersion = packageVersionInspector.packageVersion(promoted);
                if (!packageVersionInspector.isEpub34(promotedVersion)) {
                    throw new IllegalArgumentException("EPUB 3.4 promotion did not produce an OPF 3.4 package");
                }
                EpubSemanticVerifier.Verification promotionVerification = semanticVerifier.verify(source, promoted);
                if (!promotionVerification.equivalent()) {
                    throw new IllegalArgumentException("EPUB 3.4 promotion changed publication semantics: "
                            + promotionVerification.differences());
                }
                source = promoted;
                promotionWarning = "Promoted OPF package version from " + inputPackageVersion + " to " + promotedVersion + ".";
            }
            String sourcePackageVersion = packageVersionInspector.packageVersion(source);
            EpubResourceGraph graph = graphAnalyzer.analyze(source);
            checkCancelled();
            long sourceSize = Files.size(source);
            List<String> warnings = new ArrayList<>(graph.warnings());
            warnings.add("EPUB package version: " + sourcePackageVersion + ".");
            if (promotionWarning != null) warnings.add(promotionWarning);
            int duplicateCount = graph.exactDuplicateGroups().values().stream().mapToInt(list -> list.size() - 1).sum();
            if (duplicateCount > 0) warnings.add("Exact duplicate resources detected: " + duplicateCount + ".");
            if (!graph.warnings().isEmpty()) {
                warnings.add("Resource-reference analysis is incomplete; destructive resource transforms are disabled for this EPUB.");
            }

            if (profile == EpubOptimizationProfile.NONE) {
                Acceptance acceptance = runAcceptance(source, warnings, acceptanceMode);
                if (!acceptance.passed()) throw new IllegalArgumentException("EPUB acceptance validation failed: " + acceptance.failureSummary());
                copySafely(source, target);
                return report(source, target, sourceSize, Files.size(target), 0, 0, profile, readingSystem, warnings,
                        acceptance.epubCheckStatus(), acceptance.readerStatus(), "VALIDATED_ONLY");
            }

            Path candidate = optimizerWorkspace.resolve("candidate.epub");
            Path deduped = optimizerWorkspace.resolve("deduped.epub");
            Path pruned = optimizerWorkspace.resolve("pruned.epub");
            Path normalized = optimizerWorkspace.resolve("normalized.epub");
            Path binaryOptimized = optimizerWorkspace.resolve("binary.epub");
            Path binaryWorkspace = optimizerWorkspace.resolve("binary-work");
            int removedOrphans = 0;
            int deduplicatedResources = 0;
            int optimizedImages = 0;
            int subsetFonts = 0;
            Path packagingSource = source;
            EpubResourceGraph workingGraph = graph;
            if (duplicateCount > 0 && workingGraph.warnings().isEmpty()) {
                checkCancelled();
                int deduplicated = duplicateDeduplicator.deduplicate(source, deduped, graph);
                checkCancelled();
                if (deduplicated > 0) {
                    validator.validate(deduped);
                    EpubSemanticVerifier.Verification dedupeVerification = semanticVerifier.verify(source, deduped);
                    EpubResourceGraph dedupeGraph = graphAnalyzer.analyze(deduped);
                    if (dedupeVerification.equivalent() && dedupeGraph.warnings().isEmpty()) {
                        packagingSource = deduped;
                        workingGraph = dedupeGraph;
                        deduplicatedResources = deduplicated;
                        warnings.add("Deduplicated exact binary resources: " + deduplicated + ".");
                    } else if (!dedupeVerification.equivalent()) {
                        warnings.add("Exact resource deduplication rejected: semantic differences " + dedupeVerification.differences());
                    } else {
                        warnings.add("Exact resource deduplication rejected: resulting resource graph has analysis warnings " + dedupeGraph.warnings());
                    }
                }
            }
            int orphanCandidates = workingGraph.conservativeOrphans().size();
            int safeOrphanCandidates = workingGraph.safelyPrunableOrphans().size();
            if (orphanCandidates > safeOrphanCandidates) warnings.add("Orphan candidates requiring conservative retention: " + (orphanCandidates - safeOrphanCandidates) + ".");
            if (safeOrphanCandidates > 0) {
                checkCancelled();
                int removed = resourcePruner.prune(packagingSource, pruned, workingGraph, workingGraph.safelyPrunableOrphans());
                checkCancelled();
                validator.validate(pruned);
                EpubSemanticVerifier.Verification pruneVerification = semanticVerifier.verify(source, pruned);
                EpubResourceGraph prunedGraph = graphAnalyzer.analyze(pruned);
                if (pruneVerification.equivalent() && prunedGraph.warnings().isEmpty()) {
                    packagingSource = pruned;
                    workingGraph = prunedGraph;
                    removedOrphans = removed;
                    warnings.add("Removed graph-proven orphan resources: " + removed + ".");
                } else if (!pruneVerification.equivalent()) {
                    warnings.add("Orphan pruning rejected: semantic differences " + pruneVerification.differences());
                } else {
                    warnings.add("Orphan pruning rejected: resulting resource graph has analysis warnings " + prunedGraph.warnings());
                }
            }
            checkCancelled();
            if (workingGraph.warnings().isEmpty()) {
                EpubSafeMarkupNormalizer.Result markup = markupNormalizer.normalize(packagingSource, normalized, workingGraph);
                checkCancelled();
                if (markup.changedResources() > 0) {
                    validator.validate(normalized);
                    EpubSemanticVerifier.Verification markupVerification = semanticVerifier.verify(source, normalized);
                    EpubResourceGraph normalizedGraph = graphAnalyzer.analyze(normalized);
                    if (markupVerification.equivalent() && normalizedGraph.warnings().isEmpty()) {
                        packagingSource = normalized;
                        workingGraph = normalizedGraph;
                        warnings.add("Safely normalized markup/CSS resources: " + markup.changedResources() + ".");
                    } else if (!markupVerification.equivalent()) {
                        warnings.add("Markup/CSS normalization rejected: semantic differences " + markupVerification.differences());
                    } else {
                        warnings.add("Markup/CSS normalization rejected: resulting resource graph has analysis warnings " + normalizedGraph.warnings());
                    }
                }
            }
            checkCancelled();
            if (workingGraph.warnings().isEmpty()) {
                EpubBinaryResourceOptimizer.Result binary = binaryResourceOptimizer.optimize(
                        packagingSource, binaryOptimized, workingGraph, profile, readingSystem, binaryWorkspace);
                warnings.addAll(binary.warnings());
                if (binary.changed()) {
                    validator.validate(binary.output());
                    EpubSemanticVerifier.Verification binaryVerification = semanticVerifier.verify(source, binary.output());
                    EpubResourceGraph binaryGraph = graphAnalyzer.analyze(binary.output());
                    if (binaryVerification.equivalent() && binaryGraph.warnings().isEmpty()) {
                        packagingSource = binary.output();
                        workingGraph = binaryGraph;
                        optimizedImages = binary.optimizedImages();
                        subsetFonts = binary.subsetFonts();
                        if (optimizedImages > 0) warnings.add("Lossless image resources optimized: " + optimizedImages + ".");
                        if (subsetFonts > 0) warnings.add("Verified font subsets accepted: " + subsetFonts + ".");
                    } else if (!binaryVerification.equivalent()) {
                        warnings.add("Binary resource optimization rejected: semantic differences " + binaryVerification.differences());
                    } else {
                        warnings.add("Binary resource optimization rejected: resulting resource graph has analysis warnings " + binaryGraph.warnings());
                    }
                }
            }
            checkCancelled();
            packager.repack(packagingSource, candidate, EpubPackagingProfile.compatible());
            checkCancelled();
            validator.validate(candidate);
            String candidatePackageVersion = packageVersionInspector.packageVersion(candidate);
            if (!sourcePackageVersion.equals(candidatePackageVersion)) {
                warnings.add("Optimized candidate rejected because OPF package version changed from " + sourcePackageVersion + " to " + candidatePackageVersion + ".");
                Acceptance fallback = validateFallbackSource(source, warnings, acceptanceMode);
                copySafely(source, target);
                return report(source, target, sourceSize, Files.size(target), 0, 0, profile, readingSystem, warnings,
                        fallback.epubCheckStatus(), fallback.readerStatus(), "PACKAGE_VERSION_CHANGED_SOURCE_KEPT");
            }
            if (profile == EpubOptimizationProfile.MODERN_EPUB34 && !packageVersionInspector.isEpub34(candidatePackageVersion)) {
                warnings.add("Optimized candidate rejected because MODERN_EPUB34 output does not declare EPUB package version 3.4.");
                Acceptance fallback = validateFallbackSource(source, warnings, acceptanceMode);
                copySafely(source, target);
                return report(source, target, sourceSize, Files.size(target), 0, 0, profile, readingSystem, warnings,
                        fallback.epubCheckStatus(), fallback.readerStatus(), "EPUB34_PACKAGE_VERSION_REJECTED");
            }
            EpubSemanticVerifier.Verification verification = semanticVerifier.verify(source, candidate);
            if (!verification.equivalent()) {
                warnings.add("Optimized candidate rejected: semantic differences " + verification.differences());
                Acceptance fallback = validateFallbackSource(source, warnings, acceptanceMode);
                copySafely(source, target);
                return report(source, target, sourceSize, Files.size(target), 0, 0, profile, readingSystem, warnings,
                        fallback.epubCheckStatus(), fallback.readerStatus(), "REJECTED_SOURCE_KEPT");
            }
            checkCancelled();
            Acceptance acceptance = runAcceptance(candidate, warnings, acceptanceMode);
            checkCancelled();
            long candidateSize = Files.size(candidate);
            boolean sizeAccepted = switch (profile) {
                case COMPATIBLE_EPUB33 -> candidateSize <= sourceSize;
                case BALANCED_EPUB33, MAXIMUM_EPUB33, MODERN_EPUB34 -> candidateSize < sourceSize;
                case NONE -> true;
            };
            if (!acceptance.passed()) {
                warnings.add("Optimized candidate rejected by acceptance validation: " + acceptance.failureSummary());
                Acceptance fallback = validateFallbackSource(source, warnings, acceptanceMode);
                copySafely(source, target);
                return report(source, target, sourceSize, Files.size(target), 0, 0, profile, readingSystem, warnings,
                        fallback.epubCheckStatus(), fallback.readerStatus(), "EQUIVALENT_BUT_ACCEPTANCE_REJECTED_SOURCE_KEPT");
            }
            if (!sizeAccepted) {
                warnings.add("Optimized candidate rejected because it did not reduce size under the selected profile.");
                // The emitted artifact is the original source, so acceptance must describe that
                // exact artifact rather than the candidate that is being discarded. This is
                // required in STANDARD as well as STRICT mode: an available reader/EPUBCheck
                // failure on the fallback source must never be masked by a passing candidate.
                Acceptance fallback = validateFallbackSource(source, warnings, acceptanceMode);
                copySafely(source, target);
                return report(source, target, sourceSize, Files.size(target), 0, 0, profile, readingSystem, warnings,
                        fallback.epubCheckStatus(), fallback.readerStatus(), "EQUIVALENT_SOURCE_KEPT");
            }
            publishCandidate(candidate, target);
            return report(source, target, sourceSize, Files.size(target), removedOrphans, deduplicatedResources,
                    optimizedImages, subsetFonts, profile, readingSystem, warnings,
                    acceptance.epubCheckStatus(), acceptance.readerStatus(), "EQUIVALENT");
        } finally {
            // All transformation intermediates are owned by one private workspace. Cleanup never
            // touches sibling user files and never follows symbolic links.
            try { EpubPathSafety.cleanupPrivateWorkspace(optimizerWorkspace, "EPUB optimizer workspace cleanup"); }
            catch (Exception ignored) { }
        }
    }

    private Acceptance validateFallbackSource(Path source, List<String> warnings, EpubAcceptanceMode mode) {
        Acceptance sourceAcceptance = runAcceptance(source, warnings, mode);
        if (!sourceAcceptance.passed()) {
            String prefix = mode == EpubAcceptanceMode.STRICT
                    ? "STRICT EPUB acceptance failed for source fallback: "
                    : "EPUB acceptance failed for source fallback: ";
            throw new IllegalArgumentException(prefix + sourceAcceptance.failureSummary());
        }
        return sourceAcceptance;
    }

    private Acceptance runAcceptance(Path epub, List<String> warnings, EpubAcceptanceMode mode) {
        ValidationKindState epubCheck = new ValidationKindState();
        ValidationKindState reader = new ValidationKindState();
        List<String> failures = new ArrayList<>();
        for (EpubAcceptanceValidator validator : acceptanceValidators) {
            EpubAcceptanceValidator.Result result;
            try { result = validator.validate(epub); }
            catch (RuntimeException failure) {
                result = EpubAcceptanceValidator.Result.failed(failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
            }
            ValidationKindState state = validator.kind() == EpubAcceptanceValidator.Kind.EPUBCHECK ? epubCheck : reader;
            state.record(result);
            if (!result.available() && !result.details().isBlank()) warnings.add(validator.id() + " unavailable: " + result.details());
            if (result.available() && !result.passed()) failures.add(validator.id() + ": " + result.details());
        }
        if (mode == EpubAcceptanceMode.STRICT) {
            if (!epubCheck.passed()) failures.add("STRICT acceptance requires an available passing EPUBCheck validator");
            if (!reader.passed()) failures.add("STRICT acceptance requires an available passing reader-reopen validator");
        }
        return new Acceptance(failures.isEmpty(), epubCheck.status(), reader.status(), String.join(" | ", failures));
    }

    private static final class ValidationKindState {
        private boolean available;
        private boolean passed;
        private boolean failed;

        void record(EpubAcceptanceValidator.Result result) {
            if (!result.available()) return;
            available = true;
            if (result.passed()) passed = true;
            else failed = true;
        }

        boolean passed() { return available && passed && !failed; }
        String status() {
            if (failed) return "FAILED";
            if (available && passed) return "PASSED";
            return "NOT_AVAILABLE";
        }
    }

    private record Acceptance(boolean passed, String epubCheckStatus, String readerStatus, String failureSummary) { }

    private void copySafely(Path source, Path target) throws Exception {
        publishVerified(source, target, "EPUB optimizer copy");
    }

    private EpubOptimizationReport report(Path source, Path result, long sourceSize, long resultSize, int removed, int deduplicated,
                                          EpubOptimizationProfile profile, ReadingSystemProfile readingSystem,
                                          List<String> warnings, String epubCheck, String readerReopen, String semantic) throws Exception {
        return report(source, result, sourceSize, resultSize, removed, deduplicated, 0, 0, profile, readingSystem,
                warnings, epubCheck, readerReopen, semantic);
    }

    private EpubOptimizationReport report(Path source, Path result, long sourceSize, long resultSize, int removed, int deduplicated,
                                          int optimizedImages, int subsetFonts,
                                          EpubOptimizationProfile profile, ReadingSystemProfile readingSystem,
                                          List<String> warnings, String epubCheck, String readerReopen, String semantic) throws Exception {
        long saved = Math.max(0L, sourceSize - resultSize);
        double percent = sourceSize <= 0 ? 0.0 : (saved * 100.0 / sourceSize);
        ResourceStats before = resourceStats(source);
        ResourceStats after = resourceStats(result);
        return new EpubOptimizationReport(sourceSize, resultSize, saved, percent,
                removed, deduplicated, before.images(), after.images(), optimizedImages, 0,
                before.fonts(), after.fonts(), subsetFonts, before.markup(), after.markup(),
                sourceSize, resultSize, packager.backend(), profile, readingSystem.targetStandard(), readingSystem.id(),
                epubCheck, readerReopen, semantic, warnings);
    }

    private ResourceStats resourceStats(Path epub) throws Exception {
        EpubResourceGraph graph = graphAnalyzer.analyze(epub);
        long images = 0L, fonts = 0L, markup = 0L;
        for (var node : graph.nodes().values()) {
            long size = Math.max(0L, node.size());
            String media = node.mediaType() == null ? "" : node.mediaType().toLowerCase(java.util.Locale.ROOT);
            String path = node.path().toLowerCase(java.util.Locale.ROOT);
            if (media.startsWith("image/") && !media.contains("svg")) images += size;
            else if (media.startsWith("font/") || path.matches(".*\\.(ttf|otf|woff|woff2)$")) fonts += size;
            else if (media.contains("xhtml") || media.equals("text/html") || media.equals("text/css")
                    || media.contains("xml") || media.contains("svg") || media.contains("smil")
                    || path.matches(".*\\.(xhtml|html|htm|css|xml|opf|ncx|svg|smil)$")) markup += size;
        }
        return new ResourceStats(images, fonts, markup);
    }

    private record ResourceStats(long images, long fonts, long markup) { }

    private void publishCandidate(Path candidate, Path target) throws Exception {
        publishVerified(candidate, target, "EPUB optimizer publication");
    }

    /**
     * Publishes an already accepted EPUB without exposing the caller's target to a partially copied or
     * post-publication-corrupted file. Existing targets are retained in a verified sibling backup until
     * the new bytes have been re-read successfully.
     */
    private void publishVerified(Path source, Path target, String role) throws Exception {
        Path safeSource = EpubPathSafety.requireSafeRegularFile(source, role + " source");
        Path safeTarget = EpubPathSafety.prepareTarget(target, role + " target");
        if (safeSource.equals(safeTarget)) return;
        Path parent = safeTarget.getParent();
        if (parent == null) throw new IllegalStateException(role + " target has no parent directory");
        String expectedHash = sha256NoLinks(safeSource);

        Path publicationWorkspace = EpubPathSafety.createPrivateWorkspace(parent,
                "." + safeTarget.getFileName() + ".mhl-publication-", role + " workspace");
        Path publication = publicationWorkspace.resolve("candidate.epub");
        Path previous = null;
        boolean originalDetached = false;
        boolean published = false;
        boolean success = false;
        Throwable failure = null;
        try {
            EpubPathSafety.copyFreshFile(safeSource, publication, role + " temporary copy");
            Path safePublication = EpubPathSafety.requireSafeRegularFile(publication, role + " temporary target");
            if (Files.mismatch(safeSource, safePublication) != -1L || !expectedHash.equalsIgnoreCase(sha256NoLinks(safePublication))) {
                throw new IllegalStateException(role + " temporary copy verification failed");
            }

            if (Files.exists(safeTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Path existing = EpubPathSafety.requireSafeRegularFile(safeTarget, role + " existing target");
                String existingSnapshotHash = sha256NoLinks(existing);
                previous = publicationWorkspace.resolve("previous-target.epub");
                EpubPathSafety.prepareTarget(previous, role + " previous-target backup");
                Path detached = EpubPathSafety.moveNoReplace(existing, previous, role + " existing-target detach");
                originalDetached = true;
                if (!existingSnapshotHash.equalsIgnoreCase(sha256NoLinks(detached))) {
                    try {
                        EpubPathSafety.moveNoReplace(detached, safeTarget, role + " changed-target restore");
                        previous = null;
                        originalDetached = false;
                    } catch (Exception restoreFailure) {
                        throw new IllegalStateException(role + " target changed during publication; detached bytes preserved at " + detached,
                                restoreFailure);
                    }
                    throw new IllegalStateException(role + " target changed during publication");
                }
            }

            // Fail closed on a racing destination. Do not use ATOMIC_MOVE together with replacement
            // because Java permits implementation-specific behavior when an atomic target exists.
            EpubPathSafety.moveNoReplace(safePublication, safeTarget, role + " publication");
            published = true;
            Path finalTarget = EpubPathSafety.requireSafeRegularFile(safeTarget, role + " published target");
            if (!expectedHash.equalsIgnoreCase(sha256NoLinks(finalTarget)) || Files.mismatch(safeSource, finalTarget) != -1L)
                throw new IllegalStateException(role + " final byte verification failed");
            success = true;
        } catch (Exception caught) {
            failure = caught;
            throw caught;
        } finally {
            if (!success) {
                boolean targetClear = !Files.exists(safeTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (published) targetClear = removePublishedTargetIfOwned(safeTarget, expectedHash, parent, role, failure);
                if (originalDetached && previous != null && Files.exists(previous, java.nio.file.LinkOption.NOFOLLOW_LINKS) && targetClear) {
                    try {
                        EpubPathSafety.moveNoReplace(previous, safeTarget, role + " previous-target restore");
                        previous = null;
                        originalDetached = false;
                    } catch (Exception restoreFailure) {
                        if (failure != null) failure.addSuppressed(restoreFailure);
                    }
                }
            }
            // A detached original that could not be restored is recovery evidence. Otherwise the
            // whole publication workspace is process-owned and may be removed atomically as a unit.
            if (success || !originalDetached) {
                try { EpubPathSafety.cleanupPrivateWorkspace(publicationWorkspace, role + " workspace cleanup"); }
                catch (Exception cleanupFailure) { if (failure != null) failure.addSuppressed(cleanupFailure); }
            }
        }
    }

    private boolean removePublishedTargetIfOwned(Path target, String expectedHash, Path parent, String role, Throwable failure) {
        try {
            if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true;
            Path safeTarget = EpubPathSafety.requireSafeRegularFile(target, role + " rollback target");
            if (!expectedHash.equalsIgnoreCase(sha256NoLinks(safeTarget))) return false;
            Path evidence = parent.resolve("." + target.getFileName() + ".mhl-rollback-" + UUID.randomUUID() + ".tmp");
            EpubPathSafety.prepareTarget(evidence, role + " rollback evidence");
            Path detached = EpubPathSafety.moveNoReplace(safeTarget, evidence, role + " rollback detach");
            if (!expectedHash.equalsIgnoreCase(sha256NoLinks(detached))) {
                try { EpubPathSafety.moveNoReplace(detached, target, role + " rollback evidence restore"); }
                catch (Exception restoreFailure) { if (failure != null) failure.addSuppressed(restoreFailure); }
                return false;
            }
            Files.delete(detached);
            return !Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (Exception rollbackFailure) {
            if (failure != null) failure.addSuppressed(rollbackFailure);
            return false;
        }
    }

    private String sha256NoLinks(Path file) throws Exception {
        Path safe = EpubPathSafety.requireSafeRegularFile(file, "EPUB optimizer hash input");
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (var in = Files.newInputStream(safe, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = in.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("EPUB optimization cancelled");
    }

}
