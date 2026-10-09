package com.myhomelibcorp.organizer.duplicate;

import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateDetector;
import com.myhomelibcorp.organizer.analysis.OrganizerAnalysisItem;
import com.myhomelibcorp.organizer.fingerprint.BookFingerprint;
import com.myhomelibcorp.organizer.metadata.NormalizedBookIdentity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateDecisionServiceScaleGuardTest {
    @Test
    void disablingOptionalFuzzySuggestionsKeepsAllExactAndIsbnChecks() {
        var classifier = new DuplicateDecisionService(new FuzzyDuplicateDetector());
        var first = candidate(1, "same-physical-sha", "semantic-a", "isbn-a");
        var second = candidate(2, "same-physical-sha", "semantic-b", "isbn-b");
        var third = candidate(3, "another-sha", "another-semantic", "isbn-a");

        var classified = classifier.classify(List.of(first, second, third), false);
        assertThat(classified).hasSize(3);
        assertThat(classified.get(0).duplicate().kind()).isEqualTo(DuplicateKind.NONE);
        assertThat(classified.get(1).duplicate().kind()).isEqualTo(DuplicateKind.EXACT_BYTES);
        assertThat(classified.get(2).duplicate().kind()).isEqualTo(DuplicateKind.STRONG_METADATA);
    }

    @Test
    void deterministicModeCanProcessThousandsOfItemsWithoutQuadraticFuzzyComparisons() {
        var classifier = new DuplicateDecisionService(new FuzzyDuplicateDetector());
        List<OrganizerAnalysisItem> input = new ArrayList<>();
        for (int i = 0; i < 8_000; i++) {
            input.add(candidate(i, "unique-sha-" + i, "unique-sem-" + i, ""));
        }
        var classified = classifier.classify(input, false);
        assertThat(classified).hasSize(input.size());
        assertThat(classified.get(7_999).duplicate().kind()).isEqualTo(DuplicateKind.NONE);
    }

    private static OrganizerAnalysisItem candidate(int number, String fileHash, String semanticHash, String isbn) {
        return new OrganizerAnalysisItem(
                Path.of("book-" + number + ".fb2"), "", null, null,
                new NormalizedBookIdentity("Example", List.of(), "", null, "", isbn, 80, List.of()),
                new BookFingerprint(fileHash, semanticHash, 1L, 1L),
                null, null, null, List.of());
    }
}
