package com.myhomelibcorp.organizer.analysis;

import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateDetector;
import com.myhomelibcorp.organizer.detect.SignatureBookFormatDetector;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecisionService;
import com.myhomelibcorp.organizer.fingerprint.BookFingerprintService;
import com.myhomelibcorp.organizer.metadata.MetadataNormalizationService;
import com.myhomelibcorp.organizer.metadata.OrganizerMetadataExtractor;
import com.myhomelibcorp.organizer.naming.CanonicalBookPathService;
import com.myhomelibcorp.organizer.plan.PlanLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.policy.CanonicalFormatPolicy;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class Fb2ZipOrganizerNamingRegressionTest {
    @TempDir Path temp;

    @Test
    void preservesCompoundSuffixForBothLegacyNamesAndCollisionResolution() throws Exception {
        int iteration = 0;
        for (String sourceSuffix : List.of(".fb2.zip", ".fb2zip")) {
            iteration++;
            Path input = Files.createDirectories(temp.resolve("input-" + iteration));
            Path destination = temp.resolve("library-" + iteration);
            Path source = input.resolve("Что-то кроме магии" + sourceSuffix);
            writeSingleFb2Zip(source);

            OrganizerProfile defaultProfile = OrganizerProfile.safeDefault();
            OrganizerProfile preserveFormat = new OrganizerProfile(
                    "original", "original", "{Author}", "{Title}.{ext}", "original",
                    defaultProfile.pdfPolicy(), defaultProfile.originalRetention(), defaultProfile.duplicatePolicy(),
                    defaultProfile.normalizeMetadata(), defaultProfile.validateConvertedFiles(),
                    defaultProfile.useExternalMetadataProviders(), 1, 1,
                    defaultProfile.epubOptimizationProfile());
            CanonicalBookPathService paths = new CanonicalBookPathService();
            AnalyzeLibraryOrganizationUseCase analyzer = new AnalyzeLibraryOrganizationUseCase(
                    new SignatureBookFormatDetector(), new OrganizerMetadataExtractor(),
                    new MetadataNormalizationService(), new BookFingerprintService(null),
                    new CanonicalFormatPolicy(List.of(), null), paths,
                    new DuplicateDecisionService(new FuzzyDuplicateDetector()));

            OrganizationAnalysis analysis = analyzer.analyze(input, destination, preserveFormat);
            assertThat(analysis.items()).hasSize(1);
            OrganizerAnalysisItem item = analysis.items().getFirst();
            // FB2 stores first/last separately; the library path must be surname-first.
            assertThat(item.proposedRelativePath().getName(0).toString())
                    .isEqualTo("Іваненко Іван");
            assertThat(item.detectedFormat().format()).isEqualTo("fb2_zip");
            assertThat(item.formatDecision().targetFormat()).isEqualTo("fb2_zip");
            assertThat(item.warnings()).noneMatch(message -> message.startsWith("FORMAT_MISMATCH:"));
            assertThat(item.proposedRelativePath().getFileName().toString())
                    .isEqualTo("Что-то кроме магии.fb2.zip");

            Path occupied = destination.resolve(item.proposedRelativePath());
            Files.createDirectories(occupied.getParent());
            Files.writeString(occupied, "do not overwrite", StandardCharsets.UTF_8);
            var plan = new PlanLibraryOrganizationUseCase(paths).plan(analysis, preserveFormat);
            assertThat(plan.actions()).hasSize(1);
            String targetName = plan.actions().getFirst().targetPath().getFileName().toString();
            assertThat(targetName).matches("Что-то кроме магии \\[\\p{XDigit}{8}\\]\\.fb2\\.zip");
            assertThat(Files.readString(occupied)).isEqualTo("do not overwrite");
            assertThat(Files.isRegularFile(source)).isTrue();
        }
    }

    private static void writeSingleFb2Zip(Path path) throws Exception {
        String fb2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><genre>fantasy</genre>"
                + "<author><first-name>Іван</first-name><last-name>Іваненко</last-name></author>"
                + "<book-title>Что-то кроме магии</book-title><lang>ru</lang>"
                + "</title-info></description><body><section><p>Текст</p></section></body>"
                + "</FictionBook>";
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("book.fb2"));
            out.write(fb2.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
