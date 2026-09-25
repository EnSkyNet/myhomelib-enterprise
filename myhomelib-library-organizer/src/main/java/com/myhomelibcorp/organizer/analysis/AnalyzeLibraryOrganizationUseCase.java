package com.myhomelibcorp.organizer.analysis;

import com.myhomelibcorp.organizer.detect.BookEntryNameSupport;
import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import com.myhomelibcorp.organizer.detect.DetectionConfidence;
import com.myhomelibcorp.organizer.detect.SignatureBookFormatDetector;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecisionService;
import com.myhomelibcorp.organizer.fingerprint.BookFingerprint;
import com.myhomelibcorp.organizer.fingerprint.BookFingerprintService;
import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import com.myhomelibcorp.organizer.metadata.ExtractedBookMetadata;
import com.myhomelibcorp.organizer.metadata.MetadataNormalizationService;
import com.myhomelibcorp.organizer.metadata.NormalizedBookIdentity;
import com.myhomelibcorp.organizer.metadata.OrganizerMetadataExtractor;
import com.myhomelibcorp.organizer.naming.CanonicalBookPathService;
import com.myhomelibcorp.organizer.policy.CanonicalFormatDecision;
import com.myhomelibcorp.organizer.policy.CanonicalFormatPolicy;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Read-only Analyze phase. No source or destination file is created, moved, renamed or deleted. */
@Service
public final class AnalyzeLibraryOrganizationUseCase {
    private static final long MAX_ANALYZE_FILE_BYTES = 4L * 1024L * 1024L * 1024L;
    private final SignatureBookFormatDetector detector;
    private final OrganizerMetadataExtractor metadataExtractor;
    private final MetadataNormalizationService normalizer;
    private final BookFingerprintService fingerprints;
    private final CanonicalFormatPolicy formatPolicy;
    private final CanonicalBookPathService paths;
    private final DuplicateDecisionService duplicates;

    public AnalyzeLibraryOrganizationUseCase(SignatureBookFormatDetector detector,
                                              OrganizerMetadataExtractor metadataExtractor,
                                              MetadataNormalizationService normalizer,
                                              BookFingerprintService fingerprints,
                                              CanonicalFormatPolicy formatPolicy,
                                              CanonicalBookPathService paths,
                                              DuplicateDecisionService duplicates) {
        this.detector = detector;
        this.metadataExtractor = metadataExtractor;
        this.normalizer = normalizer;
        this.fingerprints = fingerprints;
        this.formatPolicy = formatPolicy;
        this.paths = paths;
        this.duplicates = duplicates;
    }

    public OrganizationAnalysis analyze(Path sourceRoot, Path destinationRoot, OrganizerProfile profile) throws Exception {
        return analyze(sourceRoot, destinationRoot, profile, () -> false, progress -> { });
    }

    /** Analyze with cooperative cancellation and bounded parallelism. */
    public OrganizationAnalysis analyze(Path sourceRoot,
                                        Path destinationRoot,
                                        OrganizerProfile profile,
                                        BooleanSupplier cancelled,
                                        Consumer<OrganizationAnalysisProgress> progress) throws Exception {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(destinationRoot, "destinationRoot");
        OrganizerProfile activeProfile = profile == null ? OrganizerProfile.safeDefault() : profile;
        BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
        Consumer<OrganizationAnalysisProgress> progressSink = progress == null ? ignored -> { } : progress;
        Path source = OrganizerPathSafety.canonicalExistingDirectory(sourceRoot, "Organizer source root");
        Path destination = OrganizerPathSafety.canonicalForCreation(destinationRoot, "Organizer destination root");

        List<Path> files = scan(source, destination, cancellation, progressSink);
        checkCancelled(cancellation);
        List<String> warnings = new ArrayList<>();
        AtomicLong processed = new AtomicLong();
        AtomicLong analyzed = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        int analysisThreads = activeProfile.maxParallelAnalysis();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                analysisThreads, analysisThreads, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(analysisThreads, analysisThreads * 4)),
                runnable -> {
                    Thread thread = new Thread(runnable, "myhomelib-organizer-analyze");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        try {
            CompletionService<List<OrganizerAnalysisItem>> completion = new ExecutorCompletionService<>(executor);
            List<OrganizerAnalysisItem> items = new ArrayList<>();
            int submitted = 0;
            int completed = 0;
            int maxInFlight = Math.max(analysisThreads, analysisThreads * 8);
            for (Path file : files) {
                checkCancelled(cancellation);
                completion.submit(() -> {
                    checkCancelled(cancellation);
                    try {
                        List<OrganizerAnalysisItem> logicalItems = analyzePath(file, activeProfile, cancellation);
                        analyzed.addAndGet(logicalItems.size());
                        return logicalItems;
                    } catch (CancellationException cancelledFailure) {
                        throw cancelledFailure;
                    } catch (Exception failure) {
                        failed.incrementAndGet();
                        return List.of(failedItem(file, activeProfile, "ANALYZE_FAILED: " + rootMessage(failure)));
                    } finally {
                        long done = processed.incrementAndGet();
                        emit(progressSink, new OrganizationAnalysisProgress(
                                files.size(), done, analyzed.get(), failed.get(), file, false));
                    }
                });
                submitted++;
                while (submitted - completed >= maxInFlight) {
                    completed += collectOne(completion, items, warnings, failed, cancellation);
                }
            }

            while (completed < submitted) {
                completed += collectOne(completion, items, warnings, failed, cancellation);
            }
            items.sort(Comparator
                    .comparing((OrganizerAnalysisItem item) -> item.sourcePath().toString(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(OrganizerAnalysisItem::sourceEntry, String.CASE_INSENSITIVE_ORDER));
            emit(progressSink, new OrganizationAnalysisProgress(
                    files.size(), processed.get(), analyzed.get(), failed.get(), null, false));
            return new OrganizationAnalysis(source, destination, Instant.now(), duplicates.classify(items), warnings);
        } finally {
            executor.shutdownNow();
        }
    }

    private int collectOne(CompletionService<List<OrganizerAnalysisItem>> completion,
                           List<OrganizerAnalysisItem> items, List<String> warnings, AtomicLong failed,
                           BooleanSupplier cancellation) throws Exception {
        checkCancelled(cancellation);
        try {
            items.addAll(completion.take().get());
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof CancellationException cancellationException) throw cancellationException;
            failed.incrementAndGet();
            warnings.add("Analyzer item failed: " + rootMessage(cause));
        }
        return 1;
    }

    private List<OrganizerAnalysisItem> analyzePath(Path file, OrganizerProfile profile, BooleanSupplier cancelled) throws Exception {
        // Detection, metadata extraction and fingerprinting intentionally use separate readers.
        // Keep one pathname identity around the whole sequence so a replacement cannot make a
        // single analysis item describe multiple physical versions of the source.
        BasicFileAttributes sourceAttributes = safeRegularFileAttributes(file, "Organizer analysis source");
        long size = sourceAttributes.size();
        DetectedBookFormat detected = detector.detect(file);
        requireSameSourceIdentity(file, sourceAttributes, "format detection");

        List<OrganizerAnalysisItem> result;
        if (size <= 0) {
            result = List.of(failedItem(file, profile, detected, size, "EMPTY_FILE: файл порожній"));
        } else if (size > MAX_ANALYZE_FILE_BYTES) {
            result = List.of(failedItem(file, profile, detected, size,
                    "FILE_TOO_LARGE: файл перевищує ліміт аналізу 4 GiB"));
        } else if ("book_archive".equals(detected.format())) {
            result = analyzeBookArchive(file, profile, cancelled);
        } else {
            result = List.of(analyzeMaterialized(file, "", file, detected, profile, List.of()));
        }
        requireSameSourceIdentity(file, sourceAttributes, "analysis");
        return result;
    }

    /**
     * Expands a multi-book ZIP into logical candidates without touching source/destination trees.
     * Each entry is materialized only in a short-lived analysis temp directory and removed afterwards.
     */
    private List<OrganizerAnalysisItem> analyzeBookArchive(Path archive, OrganizerProfile profile,
                                                            BooleanSupplier cancelled) throws Exception {
        Path tempRoot = Files.createTempDirectory("myhomelib-organizer-archive-analysis-");
        try (ZipFile zip = SafeZipSupport.openDetected(archive, "Organizer archive")) {
            List<ZipArchiveEntry> candidates = new ArrayList<>();
            Set<String> names = new HashSet<>();
            long declaredTotal = 0L;
            int inspected = 0;
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                checkCancelled(cancelled);
                if (++inspected > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                    return List.of(failedItem(archive, profile, "ARCHIVE_UNSAFE: перевищено ліміт кількості ZIP entries"));
                }
                ZipArchiveEntry entry = entries.nextElement();
                String name = entry.getName();
                if (unsafeEntry(name)) {
                    return List.of(failedItem(archive, profile, "ARCHIVE_UNSAFE: небезпечний шлях ZIP entry"));
                }
                if (entry.isDirectory() || !isBookEntryName(name)) continue;
                String collisionKey = name.replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!names.add(collisionKey)) {
                    return List.of(failedItem(archive, profile,
                            "ARCHIVE_UNSAFE: дубльоване ім'я книжкового ZIP entry: " + name));
                }
                if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
                    return List.of(failedItem(archive, profile,
                            "ARCHIVE_UNSAFE: ZIP entry перевищує 512 MiB: " + name));
                }
                if (entry.getCompressedSize() > 0 && entry.getSize() > 0
                        && exceedsCompressionRatio(entry.getSize(), entry.getCompressedSize())) {
                    return List.of(failedItem(archive, profile,
                            "ARCHIVE_UNSAFE: підозрілий compression ratio ZIP entry: " + name));
                }
                if (entry.getSize() > 0) {
                    declaredTotal = safeAdd(declaredTotal, entry.getSize());
                    if (declaredTotal > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                        return List.of(failedItem(archive, profile,
                                "ARCHIVE_UNSAFE: сумарний declared payload перевищує 1 GiB"));
                    }
                }
                candidates.add(entry);
            }
            if (candidates.size() < 2) {
                return List.of(failedItem(archive, profile,
                        "ARCHIVE_INCONSISTENT: multi-book ZIP не містить щонайменше двох підтримуваних book entries"));
            }

            List<OrganizerAnalysisItem> result = new ArrayList<>(candidates.size());
            long[] totalExtracted = {0L};
            int index = 0;
            for (ZipArchiveEntry entry : candidates) {
                checkCancelled(cancelled);
                index++;
                Path materialized = tempRoot.resolve(String.format(Locale.ROOT, "%05d", index)).resolve(safeLeafName(entry.getName()));
                try {
                    materializeEntry(zip, entry, materialized, totalExtracted, cancelled);
                    DetectedBookFormat entryFormat = detector.detect(materialized);
                    List<String> extraWarnings = List.of(
                            "ARCHIVE_ENTRY: " + entry.getName(),
                            "Архів містить " + candidates.size() + " книг; logical candidate " + index + "/" + candidates.size(),
                            "Оригінальний multi-book ZIP зберігається у v1; Organizer не видаляє контейнер автоматично");
                    result.add(analyzeMaterialized(archive, entry.getName(), materialized, entryFormat, profile, extraWarnings));
                } catch (CancellationException cancelledFailure) {
                    throw cancelledFailure;
                } catch (Exception failure) {
                    result.add(failedArchiveEntry(archive, entry.getName(), profile,
                            "ARCHIVE_ENTRY_FAILED: " + rootMessage(failure)));
                } finally {
                    Files.deleteIfExists(materialized);
                }
            }
            return List.copyOf(result);
        } finally {
            deleteTree(tempRoot);
        }
    }

    private OrganizerAnalysisItem analyzeMaterialized(Path logicalSource, String sourceEntry, Path materialized,
                                                       DetectedBookFormat detected, OrganizerProfile profile,
                                                       List<String> extraWarnings) throws Exception {
        long size = safeRegularFileAttributes(materialized, "Organizer analysis materialized source").size();
        List<String> warnings = new ArrayList<>(extraWarnings == null ? List.of() : extraWarnings);
        if (!detected.known()) warnings.add("UNSUPPORTED_FORMAT: " + detected.reason());
        String mismatch = formatExtensionMismatch(detected);
        if (!mismatch.isBlank()) warnings.add(mismatch);
        ExtractedBookMetadata raw = metadataExtractor.extract(materialized, detected);
        warnings.addAll(raw.warnings());
        NormalizedBookIdentity identity = normalizer.normalize(raw, profile.normalizeMetadata());
        BookFingerprint fingerprint = fingerprints.fingerprint(materialized, detected);
        if (sourceEntry != null && !sourceEntry.isBlank()) {
            long containerMtime = safeRegularFileAttributes(logicalSource, "Organizer archive source")
                    .lastModifiedTime().toMillis();
            fingerprint = new BookFingerprint(fingerprint.fileSha256(), fingerprint.semanticSha256(), fingerprint.size(),
                    containerMtime);
        }
        CanonicalFormatDecision formatDecision = formatPolicy.decide(detected, profile);
        String extension = formatDecision.targetFormat().isBlank() ? detected.extension() : formatDecision.targetFormat();
        Path relative = paths.relativePath(identity, profile, extension.isBlank() ? "bin" : extension);
        return new OrganizerAnalysisItem(logicalSource, sourceEntry, detected, raw, identity, fingerprint,
                formatDecision, relative, null, warnings);
    }

    private OrganizerAnalysisItem failedArchiveEntry(Path archive, String sourceEntry, OrganizerProfile profile, String reason) {
        String leaf = safeLeafName(sourceEntry);
        DetectedBookFormat detected = new DetectedBookFormat("unknown", "application/octet-stream",
                DetectionConfidence.UNKNOWN, extension(Path.of(leaf)), false, reason);
        ExtractedBookMetadata raw = new ExtractedBookMetadata(filenameTitle(Path.of(leaf)), List.of(), "", "", "", "", "", null, "",
                List.of(), false, detected.format(), detected.confidence(), List.of(reason));
        NormalizedBookIdentity identity = normalizer.normalize(raw, profile.normalizeMetadata());
        BookFingerprint fingerprint = new BookFingerprint("", "", 0L, 0L);
        CanonicalFormatDecision decision = new CanonicalFormatDecision(detected.format(), false, false, reason);
        Path relative = paths.relativePath(identity, profile, detected.extension().isBlank() ? "bin" : detected.extension());
        return new OrganizerAnalysisItem(archive, sourceEntry, detected, raw, identity, fingerprint, decision, relative, null, List.of(reason));
    }

    private OrganizerAnalysisItem failedItem(Path file, OrganizerProfile profile, String reason) {
        long size = 0L;
        try { size = safeRegularFileAttributes(file, "Organizer failed-item source").size(); } catch (Exception ignored) { }
        return failedItem(file, profile, new DetectedBookFormat("unknown", "application/octet-stream",
                DetectionConfidence.UNKNOWN, extension(file), false, reason), size, reason);
    }

    private OrganizerAnalysisItem failedItem(Path file, OrganizerProfile profile, DetectedBookFormat detected, long size, String reason) {
        String title = filenameTitle(file);
        ExtractedBookMetadata raw = new ExtractedBookMetadata(title, List.of(), "", "", "", "", "", null, "",
                List.of(), false, detected.format(), detected.confidence(), List.of(reason));
        NormalizedBookIdentity identity = normalizer.normalize(raw, profile.normalizeMetadata());
        BookFingerprint fingerprint = new BookFingerprint("", "", Math.max(0L, size), 0L);
        CanonicalFormatDecision decision = new CanonicalFormatDecision(detected.format(), false, false, reason);
        Path relative = paths.relativePath(identity, profile, detected.extension().isBlank() ? "bin" : detected.extension());
        return new OrganizerAnalysisItem(file, detected, raw, identity, fingerprint, decision, relative, null, List.of(reason));
    }

    private void materializeEntry(ZipFile zip, ZipArchiveEntry entry, Path target, long[] totalExtracted,
                                  BooleanSupplier cancelled) throws Exception {
        OrganizerPathSafety.createDirectoriesNoLinks(target.getParent(), "Organizer analysis extraction directory");
        long entryBytes = 0L;
        boolean success = false;
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = OrganizerPathSafety.openFreshOutputStream(target, "Organizer analysis extraction target")) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                checkCancelled(cancelled);
                if (read == 0) continue;
                entryBytes = safeAdd(entryBytes, read);
                totalExtracted[0] = safeAdd(totalExtracted[0], read);
                if (entryBytes > ArchiveSafetyLimits.MAX_ENTRY_BYTES)
                    throw new IllegalArgumentException("Archive entry exceeds 512 MiB extraction limit");
                if (totalExtracted[0] > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES)
                    throw new IllegalArgumentException("Archive exceeds 1 GiB cumulative extraction limit");
                out.write(buffer, 0, read);
            }
            success = true;
        } finally {
            if (!success) Files.deleteIfExists(target);
        }
        if (entry.getSize() >= 0 && entryBytes != entry.getSize()) {
            throw new IllegalArgumentException("Archive entry size changed while reading");
        }
    }

    private String formatExtensionMismatch(DetectedBookFormat detected) {
        if (detected == null || !detected.known()) return "";
        String ext = detected.extension() == null ? "" : detected.extension().toLowerCase(Locale.ROOT);
        if (ext.isBlank()) return "FORMAT_MISMATCH: сигнатура визначає формат " + detected.format() + ", але розширення файла відсутнє";
        boolean compatible = switch (detected.format()) {
            case "epub" -> ext.equals("epub");
            case "pdf" -> ext.equals("pdf");
            case "fb2" -> ext.equals("fb2");
            case "fb2_zip", "book_archive", "zip" -> ext.equals("zip");
            case "docx" -> ext.equals("docx");
            case "odt" -> ext.equals("odt");
            case "html" -> ext.equals("html") || ext.equals("htm");
            case "htmlz" -> ext.equals("htmlz");
            case "rtf" -> ext.equals("rtf");
            case "txt" -> ext.equals("txt");
            case "markdown" -> ext.equals("md") || ext.equals("markdown");
            case "mobi" -> ext.equals("mobi");
            case "prc" -> ext.equals("prc");
            case "azw" -> ext.equals("azw");
            case "azw3" -> ext.equals("azw3");
            case "azw4" -> ext.equals("azw4");
            default -> true;
        };
        return compatible ? "" : "FORMAT_MISMATCH: сигнатура визначає " + detected.format()
                + ", але розширення файла ." + ext;
    }

    private boolean isBookEntryName(String name) {
        return BookEntryNameSupport.isSupportedBookEntry(name);
    }

    private boolean unsafeEntry(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) return true;
        for (String part : name.replace('\\', '/').split("/")) if ("..".equals(part)) return true;
        return false;
    }

    private boolean exceedsCompressionRatio(long uncompressed, long compressed) {
        if (compressed <= 0 || uncompressed <= 0) return false;
        if (compressed > Long.MAX_VALUE / ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) return false;
        return uncompressed > compressed * ArchiveSafetyLimits.MAX_COMPRESSION_RATIO;
    }

    private long safeAdd(long left, long right) {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private String safeLeafName(String entry) {
        String normalized = entry == null ? "book.bin" : entry.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        leaf = leaf.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (leaf.isBlank()) leaf = "book.bin";
        return leaf.length() > 180 ? leaf.substring(leaf.length() - 180) : leaf;
    }

    private String filenameTitle(Path file) {
        String name = file == null || file.getFileName() == null ? "Untitled" : file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return name.substring(0, name.length() - 8);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String extension(Path file) {
        if (file == null || file.getFileName() == null) return "";
        String name = file.getFileName().toString(); int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private List<Path> scan(Path source,
                            Path destination,
                            BooleanSupplier cancelled,
                            Consumer<OrganizationAnalysisProgress> progress) throws Exception {
        List<Path> files = new ArrayList<>();
        AtomicLong discovered = new AtomicLong();
        Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                checkCancelled(cancelled);
                if (!dir.equals(source) && (attrs.isSymbolicLink() || attrs.isOther() || Files.isSymbolicLink(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(source) && dir.getFileName() != null) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".myhomelib-trash") || name.equals(".myhomelib-originals") || name.equals(".myhomelib"))
                        return FileVisitResult.SKIP_SUBTREE;
                }
                Path normalized = dir.toAbsolutePath().normalize();
                if (!source.equals(destination) && destination.startsWith(source) && normalized.startsWith(destination)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                checkCancelled(cancelled);
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                    Path normalized = file.toAbsolutePath().normalize();
                    files.add(normalized);
                    long count = discovered.incrementAndGet();
                    emit(progress, new OrganizationAnalysisProgress(count, 0, 0, 0, normalized, true));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (Files.isSymbolicLink(root)) {
                Files.deleteIfExists(root);
                return;
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                // The temp directory was replaced. Preserve the foreign entry rather than
                // deleting a path we no longer own.
                return;
            }
            OrganizerPathSafety.rejectLinkLikeExistingComponents(root, "Organizer analysis cleanup");
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
                    if (exc != null) throw exc; Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) { }
    }


    private static void requireSameSourceIdentity(Path file, BasicFileAttributes expected, String stage) throws java.io.IOException {
        BasicFileAttributes current = safeRegularFileAttributes(file, "Organizer analysis source after " + stage);
        if (expected.size() != current.size()
                || !expected.lastModifiedTime().equals(current.lastModifiedTime())
                || !sameFileKey(expected.fileKey(), current.fileKey())) {
            throw new java.io.IOException("Organizer analysis source changed during " + stage + ": " + file);
        }
    }

    private static boolean sameFileKey(Object left, Object right) {
        // Some file-system providers do not expose fileKey; size+mtime are the best identity
        // available in that case. Providers that expose a key must keep it stable as well.
        return left == null || right == null || Objects.equals(String.valueOf(left), String.valueOf(right));
    }

    private static BasicFileAttributes safeRegularFileAttributes(Path file, String role) throws java.io.IOException {
        Path safe = OrganizerPathSafety.canonicalExistingFile(file, role);
        BasicFileAttributes attributes = Files.readAttributes(
                safe, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isOther() || Files.isSymbolicLink(safe)) {
            throw new java.io.IOException(role + " is not a safe regular file: " + safe);
        }
        return attributes;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("Library organization analysis cancelled");
        }
    }

    private static void emit(Consumer<OrganizationAnalysisProgress> progress, OrganizationAnalysisProgress snapshot) {
        try {
            progress.accept(snapshot);
        } catch (RuntimeException ignored) {
            // A UI/progress observer must not be allowed to break the read-only analysis.
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && cause.getCause() != null) cause = cause.getCause();
        return cause == null ? "unknown" : String.valueOf(cause.getMessage());
    }
}
