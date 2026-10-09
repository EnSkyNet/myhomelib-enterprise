package com.myhomelibcorp.organizer.plan;

import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.organizer.analysis.OrganizationAnalysis;
import com.myhomelibcorp.organizer.analysis.OrganizerAnalysisItem;
import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import com.myhomelibcorp.organizer.detect.DetectionConfidence;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecision;
import com.myhomelibcorp.organizer.duplicate.DuplicateKind;
import com.myhomelibcorp.organizer.fingerprint.BookFingerprint;
import com.myhomelibcorp.organizer.metadata.ExtractedBookMetadata;
import com.myhomelibcorp.organizer.metadata.NormalizedAuthor;
import com.myhomelibcorp.organizer.metadata.NormalizedBookIdentity;
import com.myhomelibcorp.organizer.naming.CanonicalBookPathService;
import com.myhomelibcorp.organizer.policy.CanonicalFormatDecision;
import com.myhomelibcorp.organizer.policy.OrganizerDuplicatePolicy;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import com.myhomelibcorp.organizer.policy.OriginalRetentionPolicy;
import com.myhomelibcorp.organizer.policy.PdfPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanLibraryOrganizationUseCaseTest {
    @TempDir Path temp;

    private final PlanLibraryOrganizationUseCase useCase =
            new PlanLibraryOrganizationUseCase(new CanonicalBookPathService());

    @Test
    void resolvesTwoLogicalBooksThatWantTheSameFinalPathWithoutOverwrite() {
        Path sourceRoot = temp.resolve("source");
        Path destination = temp.resolve("library");
        OrganizerAnalysisItem first = item(sourceRoot.resolve("a.fb2"), "", "aaaaaaaa11111111", DuplicateDecision.none());
        OrganizerAnalysisItem second = item(sourceRoot.resolve("b.fb2"), "", "bbbbbbbb22222222", DuplicateDecision.none());

        OrganizationPlan plan = useCase.plan(
                new OrganizationAnalysis(sourceRoot, destination, Instant.now(), List.of(first, second), List.of()),
                profile(OrganizerDuplicatePolicy.KEEP_ALL));

        assertThat(plan.actions()).hasSize(2);
        assertThat(plan.actions().get(0).targetPath()).isNotEqualTo(plan.actions().get(1).targetPath());
        assertThat(plan.actions().get(1).targetPath().getFileName().toString()).contains("bbbbbbbb");
        assertThat(plan.actions().get(1).issues()).extracting(PlanIssue::code).contains("TARGET_COLLISION_RESOLVED");
        assertThat(plan.actions()).allSatisfy(item -> assertThat(item.reason()).isNotBlank());
    }

    @Test
    void fuzzyDuplicateIsNeverAutomaticallyDeletedUnderDeleteExactOnly() {
        DuplicateDecision fuzzy = new DuplicateDecision(
                DuplicateKind.FUZZY_CANDIDATE, temp.resolve("library/existing.epub"), 0.93, "High metadata similarity");
        OrganizerAnalysisItem analysisItem = item(temp.resolve("source/book.fb2"), "", "0123456789abcdef", fuzzy);

        OrganizationPlanItem planned = useCase.plan(
                new OrganizationAnalysis(temp.resolve("source"), temp.resolve("library"), Instant.now(), List.of(analysisItem), List.of()),
                profile(OrganizerDuplicatePolicy.DELETE_EXACT_ONLY)).actions().get(0);

        assertThat(planned.action()).isEqualTo(OrganizationAction.REVIEW_DUPLICATE);
        assertThat(planned.reason()).contains("High metadata similarity");
    }

    @Test
    void exactByteDuplicateIsDeletableOnlyForOrdinaryFile() {
        Path matched = temp.resolve("library/existing.epub");
        DuplicateDecision exact = new DuplicateDecision(DuplicateKind.EXACT_BYTES, matched, 1.0, "Same SHA-256");

        OrganizerAnalysisItem ordinary = item(temp.resolve("source/book.fb2"), "", "0123456789abcdef", exact);
        OrganizerAnalysisItem archived = item(temp.resolve("source/books.zip"), "inside/book.fb2", "fedcba9876543210", exact);
        OrganizationPlan plan = useCase.plan(
                new OrganizationAnalysis(temp.resolve("source"), temp.resolve("library"), Instant.now(), List.of(ordinary, archived), List.of()),
                profile(OrganizerDuplicatePolicy.DELETE_EXACT_ONLY));

        assertThat(plan.actions().get(0).action()).isEqualTo(OrganizationAction.DELETE_EXACT_DUPLICATE);
        assertThat(plan.actions().get(1).action()).isEqualTo(OrganizationAction.REVIEW_DUPLICATE);
    }

    @Test
    void rerunInsideSameLibraryRootKeepsAlreadyCanonicalBookAsNoop() {
        Path root = temp.resolve("library");
        Path canonical = root.resolve("Автор Тест/Серія/01 - Назва.epub");
        OrganizerAnalysisItem base = item(canonical, "", "0123456789abcdef", DuplicateDecision.none());
        OrganizerAnalysisItem alreadyOrganized = new OrganizerAnalysisItem(
                canonical, "",
                new DetectedBookFormat("epub", "application/epub+zip", DetectionConfidence.CERTAIN, "epub", true, "EPUB container"),
                base.extractedMetadata(), base.identity(), base.fingerprint(),
                new CanonicalFormatDecision("epub", false, true, "Already canonical EPUB"),
                Path.of("Автор Тест/Серія/01 - Назва.epub"), DuplicateDecision.none(), List.of());

        OrganizationPlan plan = useCase.plan(
                new OrganizationAnalysis(root, root, Instant.now(), List.of(alreadyOrganized), List.of()),
                profile(OrganizerDuplicatePolicy.KEEP_ALL));

        assertThat(plan.actions()).singleElement().satisfies(item -> {
            assertThat(item.targetPath()).isEqualTo(canonical);
            assertThat(item.action()).isEqualTo(OrganizationAction.NOOP);
        });
    }

    private OrganizerAnalysisItem item(Path source, String sourceEntry, String sha, DuplicateDecision duplicate) {
        DetectedBookFormat format = new DetectedBookFormat(
                "fb2", "application/x-fictionbook+xml", DetectionConfidence.CERTAIN, "fb2", false, "XML root");
        ExtractedBookMetadata extracted = new ExtractedBookMetadata(
                "Назва", List.of("Автор Тест"), "Серія", "1", "uk", "", "", null,
                "", List.of("prose"), false, "fb2", DetectionConfidence.CERTAIN, List.of());
        NormalizedBookIdentity identity = new NormalizedBookIdentity(
                "Назва", List.of(new NormalizedAuthor("Автор Тест", "Автор", "", "Тест", true)),
                "Серія", BigDecimal.ONE, "uk", "", 100, List.of());
        BookFingerprint fingerprint = new BookFingerprint(sha, "semantic-" + sha, 100L, 1L);
        CanonicalFormatDecision decision = new CanonicalFormatDecision("epub", true, true, "FB2 is converted to canonical EPUB3");
        return new OrganizerAnalysisItem(source, sourceEntry, format, extracted, identity, fingerprint, decision,
                Path.of("Автор Тест/Серія/01 - Назва.epub"), duplicate, List.of());
    }

    private OrganizerProfile profile(OrganizerDuplicatePolicy duplicatePolicy) {
        return new OrganizerProfile("test", "Test", "{Author}/{?Series:{Series}/}",
                "{?Series:{SeriesNo:00} - }{Title}.{ext}", "epub", PdfPolicy.KEEP_PDF,
                OriginalRetentionPolicy.KEEP_ORIGINAL, duplicatePolicy, true, true, false,
                2, 1, EpubOptimizationProfile.NONE);
    }
}
