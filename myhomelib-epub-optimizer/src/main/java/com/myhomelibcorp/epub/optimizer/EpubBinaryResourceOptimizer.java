package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.font.EpubFontAnalyzer;
import com.myhomelibcorp.epub.optimizer.font.EpubRequiredCodePointCollector;
import com.myhomelibcorp.epub.optimizer.font.FontOptimizerProvider;
import com.myhomelibcorp.epub.optimizer.font.FontQualityGate;
import com.myhomelibcorp.epub.optimizer.font.FontResourceAnalysis;
import com.myhomelibcorp.epub.optimizer.font.FontSubsetRequest;
import com.myhomelibcorp.epub.optimizer.font.FontSubsetResult;
import com.myhomelibcorp.epub.optimizer.font.RequiredCodePointCollection;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraph;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceNode;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceReplacementWriter;
import com.myhomelibcorp.epub.optimizer.image.EpubImageAnalyzer;
import com.myhomelibcorp.epub.optimizer.image.ImageOptimizationCandidate;
import com.myhomelibcorp.epub.optimizer.image.ImageOptimizationRequest;
import com.myhomelibcorp.epub.optimizer.image.ImageOptimizerProvider;
import com.myhomelibcorp.epub.optimizer.image.ImageQualityGate;
import com.myhomelibcorp.epub.optimizer.image.ImageResourceAnalysis;
import com.myhomelibcorp.epub.optimizer.profile.ReadingSystemProfile;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Optional binary-resource optimization coordinator.
 *
 * <p>v1 is intentionally fail-closed: only same-path, same-codec/media-type, lossless image candidates
 * and verified same-media-type font subsets may be accepted. Cross-codec rewiring and lossy image
 * recompression remain disabled until device/quality thresholds are calibrated.</p>
 */
@Component
public final class EpubBinaryResourceOptimizer {
    private final EpubImageAnalyzer imageAnalyzer;
    private final ImageQualityGate imageQualityGate;
    private final List<ImageOptimizerProvider> imageProviders;
    private final EpubFontAnalyzer fontAnalyzer;
    private final EpubRequiredCodePointCollector codePointCollector;
    private final FontQualityGate fontQualityGate;
    private final List<FontOptimizerProvider> fontProviders;
    private final EpubResourceReplacementWriter replacementWriter;

    public EpubBinaryResourceOptimizer(EpubImageAnalyzer imageAnalyzer,
                                       ImageQualityGate imageQualityGate,
                                       List<ImageOptimizerProvider> imageProviders,
                                       EpubFontAnalyzer fontAnalyzer,
                                       EpubRequiredCodePointCollector codePointCollector,
                                       FontQualityGate fontQualityGate,
                                       List<FontOptimizerProvider> fontProviders,
                                       EpubResourceReplacementWriter replacementWriter) {
        this.imageAnalyzer = Objects.requireNonNull(imageAnalyzer, "imageAnalyzer");
        this.imageQualityGate = Objects.requireNonNull(imageQualityGate, "imageQualityGate");
        this.imageProviders = imageProviders == null ? List.of() : imageProviders.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(ImageOptimizerProvider::id)).toList();
        this.fontAnalyzer = Objects.requireNonNull(fontAnalyzer, "fontAnalyzer");
        this.codePointCollector = Objects.requireNonNull(codePointCollector, "codePointCollector");
        this.fontQualityGate = Objects.requireNonNull(fontQualityGate, "fontQualityGate");
        this.fontProviders = fontProviders == null ? List.of() : fontProviders.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(FontOptimizerProvider::id)).toList();
        this.replacementWriter = Objects.requireNonNull(replacementWriter, "replacementWriter");
    }

    public Result optimize(Path source, Path target, EpubResourceGraph graph, EpubOptimizationProfile profile,
                           ReadingSystemProfile readingSystem, Path workspace) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(readingSystem, "readingSystem");
        profile = profile == null ? EpubOptimizationProfile.BALANCED_EPUB33 : profile;
        if (profile == EpubOptimizationProfile.NONE || (imageProviders.isEmpty() && fontProviders.isEmpty())) {
            return Result.unchanged(source);
        }
        Path root = workspace.toAbsolutePath().normalize();
        EpubPathSafety.createDirectoriesNoLinks(root, "EPUB binary resource workspace");
        Map<String, Path> replacements = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int optimizedImages = optimizeImages(source, graph, profile, readingSystem, root.resolve("images"), replacements, warnings);
        int subsetFonts = optimizeFonts(source, graph, profile, readingSystem, root.resolve("fonts"), replacements, warnings);
        if (replacements.isEmpty()) return new Result(source, false, 0, 0, warnings);
        replacementWriter.replace(source, target, replacements);
        return new Result(target, true, optimizedImages, subsetFonts, warnings);
    }

    private int optimizeImages(Path epub, EpubResourceGraph graph, EpubOptimizationProfile profile,
                               ReadingSystemProfile readingSystem, Path workspace, Map<String, Path> replacements,
                               List<String> warnings) throws Exception {
        if (imageProviders.isEmpty()) return 0;
        List<ImageResourceAnalysis> images = imageAnalyzer.analyze(epub, graph);
        if (images.isEmpty()) return 0;
        EpubPathSafety.createDirectoriesNoLinks(workspace, "EPUB optimizer workspace");
        int accepted = 0;
        int resourceIndex = 0;
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            for (ImageResourceAnalysis image : images) {
                resourceIndex++;
                checkCancelled();
                EpubResourceNode node = graph.nodes().get(image.path());
                if (!eligibleNode(node)) continue;
                if (image.iccProfile() || image.orientation() != 0 || image.exif()) {
                    warnings.add("Image retained because color/orientation metadata preservation is not yet provable: " + image.path());
                    continue;
                }
                ZipEntry entry = zip.getEntry(image.path());
                if (entry == null) continue;
                Path sourceResource = materialize(zip, entry, workspace.resolve("source-" + resourceIndex + suffix(image.path())));
                ImageOptimizationCandidate best = null;
                for (ImageOptimizerProvider provider : imageProviders) {
                    if (!provider.isAvailable() || !provider.inputCodecs().contains(image.codec())) continue;
                    Path providerWorkspace = workspace.resolve(resourceIndex + "-" + safe(provider.id()));
                    EpubPathSafety.createDirectoriesNoLinks(providerWorkspace, "EPUB optimizer provider workspace");
                    ImageOptimizationRequest request = new ImageOptimizationRequest(image, profile, readingSystem,
                            0, 0, minimumSavingsBytes(profile), minimumSavingsRatio(profile), 1.0);
                    Optional<ImageOptimizationCandidate> candidateOptional;
                    try {
                        candidateOptional = provider.optimize(sourceResource, request, providerWorkspace);
                    } catch (Exception failure) {
                        warnings.add("Image optimizer " + provider.id() + " failed for " + image.path() + ": " + rootMessage(failure));
                        continue;
                    }
                    if (candidateOptional.isEmpty()) continue;
                    ImageOptimizationCandidate candidate = candidateOptional.get();
                    if (!safeCandidate(candidate.file(), providerWorkspace)) {
                        warnings.add("Image optimizer " + provider.id() + " returned an unsafe candidate path for " + image.path());
                        continue;
                    }
                    if (candidate.codec() != image.codec()) {
                        warnings.add("Cross-codec image candidate retained for future profile support but not applied in v1: " + image.path());
                        continue;
                    }
                    if (!candidate.lossless()) {
                        warnings.add("Lossy image candidate rejected until perceptual thresholds are calibrated: " + image.path());
                        continue;
                    }
                    EpubImageAnalyzer.StandaloneImageAnalysis inspected;
                    try {
                        inspected = imageAnalyzer.inspectCandidate(candidate.file(), image.codec());
                    } catch (Exception inspectFailure) {
                        warnings.add("Image optimizer " + provider.id() + " candidate could not be independently inspected for "
                                + image.path() + ": " + rootMessage(inspectFailure));
                        continue;
                    }
                    if (!inspected.signatureVerified() || inspected.byteSize() != candidate.byteSize()) {
                        warnings.add("Image optimizer " + provider.id() + " candidate signature/size verification failed for " + image.path());
                        continue;
                    }
                    if (image.codec() == com.myhomelibcorp.epub.optimizer.profile.ImageCodec.AVIF
                            || image.codec() == com.myhomelibcorp.epub.optimizer.profile.ImageCodec.JPEG_XL) {
                        warnings.add("Image candidate rejected because v1 cannot independently verify AVIF/JPEG XL image semantics: " + image.path());
                        continue;
                    }
                    if (inspected.dimensionsKnown() && (candidate.width() != inspected.width() || candidate.height() != inspected.height())) {
                        warnings.add("Image optimizer " + provider.id() + " candidate dimensions do not match its actual header for " + image.path());
                        continue;
                    }
                    if (inspected.alphaKnown() && candidate.alpha() != inspected.alpha()) {
                        warnings.add("Image optimizer " + provider.id() + " candidate alpha metadata does not match its actual header for " + image.path());
                        continue;
                    }
                    if (inspected.animationKnown() && candidate.animated() != inspected.animated()) {
                        warnings.add("Image optimizer " + provider.id() + " candidate animation metadata does not match its actual header for " + image.path());
                        continue;
                    }
                    if (image.dimensionsKnown() && (!inspected.dimensionsKnown()
                            || inspected.width() != image.width() || inspected.height() != image.height())) {
                        warnings.add("Image optimizer " + provider.id() + " candidate cannot prove preserved dimensions for " + image.path());
                        continue;
                    }
                    if (image.alphaKnown() && (!inspected.alphaKnown() || inspected.alpha() != image.alpha())) {
                        warnings.add("Image optimizer " + provider.id() + " candidate cannot prove preserved alpha semantics for " + image.path());
                        continue;
                    }
                    if (image.animationKnown() && (!inspected.animationKnown() || inspected.animated() != image.animated())) {
                        warnings.add("Image optimizer " + provider.id() + " candidate cannot prove preserved animation semantics for " + image.path());
                        continue;
                    }
                    if (image.dimensionsKnown() && (candidate.width() != image.width() || candidate.height() != image.height())) {
                        warnings.add("Image resize candidate rejected until device-size thresholds are calibrated: " + image.path());
                        continue;
                    }
                    if (!imageAnalyzer.decodedPixelsEqual(sourceResource, candidate.file(), image.codec())) {
                        warnings.add("Image optimizer " + provider.id() + " candidate failed independent decoded-pixel equality for " + image.path());
                        continue;
                    }
                    ImageQualityGate.Decision decision = imageQualityGate.evaluate(image, candidate, readingSystem,
                            minimumSavingsBytes(profile), minimumSavingsRatio(profile), 1.0);
                    if (!decision.accepted()) continue;
                    if (best == null || candidate.byteSize() < best.byteSize()) best = candidate;
                }
                if (best != null) {
                    replacements.put(image.path(), best.file());
                    accepted++;
                }
            }
        }
        return accepted;
    }

    private int optimizeFonts(Path epub, EpubResourceGraph graph, EpubOptimizationProfile profile,
                              ReadingSystemProfile readingSystem, Path workspace, Map<String, Path> replacements,
                              List<String> warnings) throws Exception {
        if (fontProviders.isEmpty()) return 0;
        List<FontResourceAnalysis> fonts = fontAnalyzer.analyze(epub, graph);
        if (fonts.isEmpty()) return 0;
        RequiredCodePointCollection required = codePointCollector.collect(epub, graph);
        warnings.addAll(required.warnings());
        if (!required.complete() || required.codePoints().isEmpty()) return 0;
        EpubPathSafety.createDirectoriesNoLinks(workspace, "EPUB optimizer workspace");
        int accepted = 0;
        int resourceIndex = 0;
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            for (FontResourceAnalysis font : fonts) {
                resourceIndex++;
                checkCancelled();
                EpubResourceNode node = graph.nodes().get(font.path());
                if (!eligibleNode(node)) continue;
                ZipEntry entry = zip.getEntry(font.path());
                if (entry == null) continue;
                Path sourceResource = materialize(zip, entry, workspace.resolve("source-" + resourceIndex + suffix(font.path())));
                FontSubsetResult best = null;
                for (FontOptimizerProvider provider : fontProviders) {
                    if (!provider.isAvailable()) continue;
                    Path providerWorkspace = workspace.resolve(resourceIndex + "-" + safe(provider.id()));
                    EpubPathSafety.createDirectoriesNoLinks(providerWorkspace, "EPUB optimizer provider workspace");
                    Optional<FontSubsetResult> candidateOptional;
                    try {
                        candidateOptional = provider.subset(sourceResource,
                                new FontSubsetRequest(font, required.codePoints(), profile, readingSystem), providerWorkspace);
                    } catch (Exception failure) {
                        warnings.add("Font optimizer " + provider.id() + " failed for " + font.path() + ": " + rootMessage(failure));
                        continue;
                    }
                    if (candidateOptional.isEmpty()) continue;
                    FontSubsetResult candidate = candidateOptional.get();
                    if (!safeCandidate(candidate.file(), providerWorkspace)) {
                        warnings.add("Font optimizer " + provider.id() + " returned an unsafe candidate path for " + font.path());
                        continue;
                    }
                    if (!normalizeMedia(candidate.mediaType()).equals(normalizeMedia(font.mediaType()))) {
                        warnings.add("Font media-type conversion is not applied in v1 because OPF/CSS rewiring would be required: " + font.path());
                        continue;
                    }
                    EpubFontAnalyzer.StandaloneFontAnalysis inspectedFont;
                    try {
                        inspectedFont = fontAnalyzer.inspectCandidate(candidate.file(), font.mediaType());
                    } catch (Exception inspectFailure) {
                        warnings.add("Font optimizer " + provider.id() + " candidate could not be independently inspected for "
                                + font.path() + ": " + rootMessage(inspectFailure));
                        continue;
                    }
                    if (!inspectedFont.signatureVerified() || inspectedFont.byteSize() != candidate.byteSize()
                            || !inspectedFont.variableFontKnown() || inspectedFont.variableFont()) {
                        warnings.add("Font optimizer " + provider.id() + " candidate failed independent SFNT/variable-font verification for " + font.path());
                        continue;
                    }
                    EpubFontAnalyzer.CodePointCoverage coverage = fontAnalyzer.verifyCodePointCoverage(candidate.file(), required.codePoints());
                    if (!coverage.verified() || !coverage.complete()) {
                        warnings.add("Font optimizer " + provider.id() + " candidate failed independent Unicode cmap coverage verification for "
                                + font.path() + " (missing=" + coverage.missingCount() + ").");
                        continue;
                    }
                    FontQualityGate.Decision decision = fontQualityGate.evaluate(font, candidate, required, readingSystem,
                            minimumSavingsBytes(profile), minimumSavingsRatio(profile));
                    if (!decision.accepted()) continue;
                    if (best == null || candidate.byteSize() < best.byteSize()) best = candidate;
                }
                if (best != null) {
                    replacements.put(font.path(), best.file());
                    accepted++;
                }
            }
        }
        return accepted;
    }

    private Path materialize(ZipFile zip, ZipEntry entry, Path target) throws Exception {
        if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())
                || ArchiveSafetyLimits.exceedsCompressionRatio(entry.getSize(), entry.getCompressedSize())) {
            throw new IllegalArgumentException("EPUB resource exceeds archive safety limits: " + entry.getName());
        }
        target = EpubPathSafety.prepareTarget(target, "EPUB resource extraction target");
        long written = 0L;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = zip.getInputStream(entry);
             var out = EpubPathSafety.openFreshOutputStream(target, "EPUB resource extraction target")) {
            for (int n; (n = in.read(buffer)) >= 0;) {
                checkCancelled();
                if (n == 0) continue;
                written += n;
                if (written > ArchiveSafetyLimits.MAX_ENTRY_BYTES)
                    throw new IllegalArgumentException("EPUB resource exceeds archive safety limit while extracting: " + entry.getName());
                out.write(buffer, 0, n);
            }
        } catch (Exception failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
        return target;
    }

    private boolean eligibleNode(EpubResourceNode node) {
        if (node == null || node.encrypted() || node.navigation() || node.spineItem()) return false;
        if (node.properties().contains("scripted")) return false;
        return true;
    }

    private boolean safeCandidate(Path candidate, Path workspace) {
        if (candidate == null || workspace == null) return false;
        try {
            Path root = workspace.toAbsolutePath().normalize();
            EpubPathSafety.rejectLinkLikeExistingComponents(root, "EPUB optimizer provider workspace");
            Path file = EpubPathSafety.requireSafeRegularFile(candidate, "EPUB optimizer provider candidate");
            return file.startsWith(root);
        } catch (Exception unsafe) {
            return false;
        }
    }

    private long minimumSavingsBytes(EpubOptimizationProfile profile) {
        return switch (profile) {
            case COMPATIBLE_EPUB33 -> 8L * 1024L;
            case BALANCED_EPUB33, MODERN_EPUB34 -> 4L * 1024L;
            case MAXIMUM_EPUB33 -> 1024L;
            case NONE -> Long.MAX_VALUE;
        };
    }

    private double minimumSavingsRatio(EpubOptimizationProfile profile) {
        return switch (profile) {
            case COMPATIBLE_EPUB33 -> 0.03;
            case BALANCED_EPUB33, MODERN_EPUB34 -> 0.02;
            case MAXIMUM_EPUB33 -> 0.01;
            case NONE -> 1.0;
        };
    }

    private String normalizeMedia(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private String suffix(String path) {
        int slash = path.lastIndexOf('/'); String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.'); return dot < 0 ? ".bin" : name.substring(dot);
    }
    private String safe(String value) { return value == null ? "provider" : value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) current = current.getCause();
        return current == null || current.getMessage() == null ? "unknown failure" : current.getMessage();
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("EPUB binary-resource optimization cancelled");
    }

    public record Result(Path output, boolean changed, int optimizedImages, int subsetFonts, List<String> warnings) {
        public Result { warnings = List.copyOf(warnings == null ? List.of() : warnings); }
        static Result unchanged(Path source) { return new Result(source, false, 0, 0, List.of()); }
    }
}
