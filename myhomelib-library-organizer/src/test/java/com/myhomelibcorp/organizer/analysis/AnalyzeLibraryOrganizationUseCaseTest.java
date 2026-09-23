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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    private ApplicationSettingsPort settings() {
        return new ApplicationSettingsPort() {
            @Override public String get(String key, String defaultValue) { return defaultValue; }
            @Override public void put(String key, String value) { }
            @Override public void remove(String key) { }
            @Override public Map<String, String> findByPrefix(String prefix) { return Map.of(); }
        };
    }
}
