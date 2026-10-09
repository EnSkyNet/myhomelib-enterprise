package com.myhomelibcorp.organizer.analysis;

import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateDetector;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.organizer.detect.SignatureBookFormatDetector;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecisionService;
import com.myhomelibcorp.organizer.fingerprint.BookFingerprintService;
import com.myhomelibcorp.organizer.metadata.MetadataNormalizationService;
import com.myhomelibcorp.organizer.metadata.OrganizerMetadataExtractor;
import com.myhomelibcorp.organizer.naming.CanonicalBookPathService;
import com.myhomelibcorp.organizer.policy.CanonicalFormatPolicy;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import com.myhomelibcorp.organizer.policy.OrganizerDuplicatePolicy;
import com.myhomelibcorp.organizer.policy.OriginalRetentionPolicy;
import com.myhomelibcorp.organizer.policy.PdfPolicy;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyzeLibraryOrganizationUseCaseTest {
    @TempDir Path temp;

    @Test
    void classifiesEverySourceAndReportsSignatureExtensionMismatchWithoutMutatingInput() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source"));
        Path destination = temp.resolve("library");
        Path disguisedFb2 = source.resolve("book.pdf");
        byte[] fb2 = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><genre>prose</genre><author><first-name>Іван</first-name>"
                + "<last-name>Тест</last-name></author><book-title>Книга</book-title><lang>uk</lang>"
                + "</title-info></description><body><section><p>Текст</p></section></body></FictionBook>")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(disguisedFb2, fb2);
        Path unknown = source.resolve("blob.bin");
        byte[] unknownBytes = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        Files.write(unknown, unknownBytes);

        AnalyzeLibraryOrganizationUseCase useCase = new AnalyzeLibraryOrganizationUseCase(
                new SignatureBookFormatDetector(),
                new OrganizerMetadataExtractor(),
                new MetadataNormalizationService(),
                new BookFingerprintService(null),
                new CanonicalFormatPolicy(java.util.List.of(), settings()),
                new CanonicalBookPathService(),
                new DuplicateDecisionService(new FuzzyDuplicateDetector()));

        OrganizationAnalysis analysis = useCase.analyze(source, destination, OrganizerProfile.safeDefault());

        assertThat(analysis.items()).hasSize(2);
        OrganizerAnalysisItem fb2Item = analysis.items().stream()
                .filter(item -> item.sourcePath().equals(disguisedFb2.toAbsolutePath().normalize()))
                .findFirst().orElseThrow();
        assertThat(fb2Item.detectedFormat().format()).isEqualTo("fb2");
        assertThat(fb2Item.warnings()).anyMatch(value -> value.startsWith("FORMAT_MISMATCH:"));
        assertThat(fb2Item.identity().title()).isEqualTo("Книга");

        OrganizerAnalysisItem unknownItem = analysis.items().stream()
                .filter(item -> item.sourcePath().equals(unknown.toAbsolutePath().normalize()))
                .findFirst().orElseThrow();
        assertThat(unknownItem.detectedFormat().known()).isFalse();
        assertThat(unknownItem.formatDecision().supported()).isFalse();
        assertThat(unknownItem.formatDecision().reason()).isNotBlank();

        assertThat(Files.readAllBytes(disguisedFb2)).isEqualTo(fb2);
        assertThat(Files.readAllBytes(unknown)).isEqualTo(unknownBytes);
        assertThat(Files.exists(destination)).isFalse();
    }

    @Test
    void explicitlyReportsFilenameOnlyMetadataCoverageForRecognizedFormat() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source-limited"));
        Path destination = temp.resolve("library-limited");
        Path rtf = source.resolve("Legacy title.rtf");
        Files.writeString(rtf, "{\\rtf1\\ansi Legacy body}", StandardCharsets.US_ASCII);

        AnalyzeLibraryOrganizationUseCase useCase = new AnalyzeLibraryOrganizationUseCase(
                new SignatureBookFormatDetector(),
                new OrganizerMetadataExtractor(),
                new MetadataNormalizationService(),
                new BookFingerprintService(null),
                new CanonicalFormatPolicy(java.util.List.of(), settings()),
                new CanonicalBookPathService(),
                new DuplicateDecisionService(new FuzzyDuplicateDetector()));

        OrganizationAnalysis analysis = useCase.analyze(source, destination, OrganizerProfile.safeDefault());

        assertThat(analysis.items()).hasSize(1);
        OrganizerAnalysisItem item = analysis.items().getFirst();
        assertThat(item.detectedFormat().format()).isEqualTo("rtf");
        assertThat(item.extractedMetadata().rawTitle()).isEqualTo("Legacy title");
        assertThat(item.extractedMetadata().rawAuthors()).isEmpty();
        assertThat(item.warnings()).contains("METADATA_LIMITED: для формату rtf доступне лише ім’я файла; Author/Series не вигадуються");
    }


    @Test
    void timesOutOneStuckFileAndContinuesInsteadOfHangingAnalysis() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source-timeout"));
        Path destination = temp.resolve("library-timeout");
        Path stuck = source.resolve("stuck.fb2");
        Files.writeString(stuck, "<FictionBook/>", StandardCharsets.UTF_8);

        AnalyzeLibraryOrganizationUseCase useCase = new AnalyzeLibraryOrganizationUseCase(
                new SignatureBookFormatDetector(),
                new OrganizerMetadataExtractor(),
                new MetadataNormalizationService(),
                new BookFingerprintService(null),
                new CanonicalFormatPolicy(java.util.List.of(), settings()),
                new CanonicalBookPathService(),
                new DuplicateDecisionService(new FuzzyDuplicateDetector()),
                1L,
                (file, profile, cancelled) -> {
                    Thread.sleep(5_000L);
                    return java.util.List.of();
                });

        long started = System.nanoTime();
        OrganizationAnalysis analysis = useCase.analyze(source, destination, OrganizerProfile.safeDefault());
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).isLessThan(3_000L);
        assertThat(analysis.items()).hasSize(1);
        assertThat(analysis.items().getFirst().sourcePath()).isEqualTo(stuck.toAbsolutePath().normalize());
        assertThat(analysis.items().getFirst().warnings())
                .anyMatch(value -> value.contains("ANALYZE_TIMEOUT"));
        assertThat(Files.readString(stuck, StandardCharsets.UTF_8)).isEqualTo("<FictionBook/>");
        assertThat(Files.exists(destination)).isFalse();
    }


    @Test
    void abortsWhenTooManyTimedOutAnalyzersIgnoreCancellationInsteadOfAccumulatingUnboundedTasks() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source-capacity"));
        Path destination = temp.resolve("library-capacity");
        for (int i = 0; i < 5; i++) {
            Files.writeString(source.resolve("stuck-" + i + ".fb2"), "<FictionBook/>", StandardCharsets.UTF_8);
        }
        AtomicBoolean release = new AtomicBoolean(false);
        AnalyzeLibraryOrganizationUseCase useCase = new AnalyzeLibraryOrganizationUseCase(
                new SignatureBookFormatDetector(),
                new OrganizerMetadataExtractor(),
                new MetadataNormalizationService(),
                new BookFingerprintService(null),
                new CanonicalFormatPolicy(java.util.List.of(), settings()),
                new CanonicalBookPathService(),
                new DuplicateDecisionService(new FuzzyDuplicateDetector()),
                1L,
                (file, profile, cancelled) -> {
                    while (!release.get()) {
                        try { Thread.sleep(100L); }
                        catch (InterruptedException ignored) { /* deliberately simulate a broken parser */ }
                    }
                    return java.util.List.of();
                });
        OrganizerProfile singleThread = new OrganizerProfile(
                "capacity", "Capacity", "{Author}", "{Title}.{ext}", "epub",
                PdfPolicy.KEEP_PDF, OriginalRetentionPolicy.KEEP_ORIGINAL, OrganizerDuplicatePolicy.REVIEW,
                true, true, false, 1, 1, EpubOptimizationProfile.BALANCED_EPUB33);

        try {
            assertThatThrownBy(() -> useCase.analyze(source, destination, singleThread))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("too many timed-out file analyzers");
        } finally {
            release.set(true);
        }
        assertThat(Files.exists(destination)).isFalse();
    }

    @Test
    void streamsPathsAfterCountingWithoutChangingProgressOrFollowingSymlinks() throws Exception {
        Path source = Files.createDirectories(temp.resolve("streaming-source"));
        Path destination = source.resolve("output");
        Files.createDirectories(destination);
        Files.createDirectories(source.resolve(".myhomelib-trash"));
        Files.writeString(source.resolve(".myhomelib-trash").resolve("skip.fb2"), "skipped");
        Files.writeString(destination.resolve("skip.fb2"), "skipped");
        for (int i = 0; i < 120; i++) {
            Files.writeString(source.resolve("item-" + i + ".fb2"), "book", StandardCharsets.UTF_8);
        }
        var visited = new java.util.concurrent.ConcurrentHashMap<Path, Boolean>();
        var updates = new java.util.concurrent.CopyOnWriteArrayList<OrganizationAnalysisProgress>();
        AnalyzeLibraryOrganizationUseCase useCase = new AnalyzeLibraryOrganizationUseCase(
                new SignatureBookFormatDetector(), new OrganizerMetadataExtractor(),
                new MetadataNormalizationService(), new BookFingerprintService(null),
                new CanonicalFormatPolicy(java.util.List.of(), settings()),
                new CanonicalBookPathService(),
                new DuplicateDecisionService(new FuzzyDuplicateDetector()),
                3L, (file, profile, cancelled) -> {
                    visited.put(file, true);
                    return java.util.List.of();
                });
        var result = useCase.analyze(source, destination, OrganizerProfile.safeDefault(),
                () -> false, updates::add);
        assertThat(result.items()).isEmpty();
        assertThat(visited).hasSize(120);
        assertThat(updates).anyMatch(update -> update.scanning() && update.filesDiscovered() == 120);
        assertThat(updates).anyMatch(update -> !update.scanning()
                && update.filesProcessed() == 120 && update.filesDiscovered() == 120);
    }

    private ApplicationSettingsPort settings() {
        return new ApplicationSettingsPort() {
            @Override public String get(String key, String defaultValue) { return defaultValue; }
            @Override public void put(String key, String value) { }
            @Override public void remove(String key) { }
            @Override public Map<String, String> findByPrefix(String prefix) { return Map.of(); }
        };
    }
}
