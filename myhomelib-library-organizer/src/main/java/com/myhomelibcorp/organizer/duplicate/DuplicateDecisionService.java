package com.myhomelibcorp.organizer.duplicate;

import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateDetector;
import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateMatch;
import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateReason;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.organizer.analysis.OrganizerAnalysisItem;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic duplicate classifier for Organizer analysis.
 *
 * <p>Levels A-C are exact/strong rules owned by Organizer. Level D deliberately delegates to the
 * existing application {@link FuzzyDuplicateDetector}; Organizer never implements a second fuzzy
 * scoring model and never turns a fuzzy match into an automatic delete/merge action.</p>
 */
@Service
public final class DuplicateDecisionService {
    private final FuzzyDuplicateDetector fuzzyDetector;

    public DuplicateDecisionService(FuzzyDuplicateDetector fuzzyDetector) {
        this.fuzzyDetector = Objects.requireNonNull(fuzzyDetector, "fuzzyDetector");
    }

    public List<OrganizerAnalysisItem> classify(List<OrganizerAnalysisItem> input) {
        return classify(input, true);
    }

    /**
     * When fuzzy analysis is disabled for very large libraries, keep all three
     * deterministic duplicate rules while avoiding quadratic pair comparisons
     * and the retention of an extra Book object for every analyzed item.
     */
    public List<OrganizerAnalysisItem> classify(List<OrganizerAnalysisItem> input, boolean includeFuzzySuggestions) {
        if (input == null || input.isEmpty()) return List.of();

        Map<String, OrganizerAnalysisItem> byBytes = new HashMap<>();
        Map<String, OrganizerAnalysisItem> bySemantic = new HashMap<>();
        Map<String, OrganizerAnalysisItem> byIsbn = new HashMap<>();
        List<OrganizerAnalysisItem> previous = includeFuzzySuggestions
                ? new ArrayList<>(input.size()) : null;
        List<Book> previousFuzzyBooks = includeFuzzySuggestions
                ? new ArrayList<>(input.size()) : null;
        List<OrganizerAnalysisItem> result = new ArrayList<>(input.size());

        for (OrganizerAnalysisItem item : input) {
            DuplicateDecision decision = DuplicateDecision.none();
            String fileHash = item.fingerprint().fileSha256();
            OrganizerAnalysisItem match = nonBlank(fileHash) ? byBytes.get(fileHash) : null;
            if (match != null) {
                decision = new DuplicateDecision(DuplicateKind.EXACT_BYTES, match.sourcePath(), match.sourceEntry(), 1.0,
                        "SHA-256 фізичного файла збігається");
            }

            if (decision.kind() == DuplicateKind.NONE) {
                String semantic = item.fingerprint().semanticSha256();
                match = nonBlank(semantic) ? bySemantic.get(semantic) : null;
                if (match != null && !Objects.equals(match.fingerprint().fileSha256(), fileHash)) {
                    decision = new DuplicateDecision(DuplicateKind.EXACT_CONTENT, match.sourcePath(), match.sourceEntry(), 0.99,
                            "Нормалізований content fingerprint збігається");
                }
            }

            if (decision.kind() == DuplicateKind.NONE) {
                String isbn = normalize(item.identity().isbn());
                match = nonBlank(isbn) ? byIsbn.get(isbn) : null;
                if (match != null) {
                    decision = new DuplicateDecision(DuplicateKind.STRONG_METADATA, match.sourcePath(), match.sourceEntry(), 0.97,
                            "ISBN збігається; потрібен review перед видаленням");
                }
            }

            Book fuzzyBook = includeFuzzySuggestions ? fuzzyProjection(item) : null;
            if (decision.kind() == DuplicateKind.NONE && fuzzyBook != null) {
                FuzzyCandidate best = null;
                for (int index = 0; index < previous.size(); index++) {
                    Book candidate = previousFuzzyBooks.get(index);
                    OrganizerAnalysisItem candidateItem = previous.get(index);
                    if (candidate == null || incompatibleKnownLanguages(item, candidateItem)) continue;
                    var evaluated = fuzzyDetector.evaluate(fuzzyBook, candidate);
                    if (evaluated.isEmpty()) continue;
                    FuzzyDuplicateMatch fuzzy = evaluated.get();
                    if (best == null || fuzzy.score() > best.match().score()) {
                        best = new FuzzyCandidate(previous.get(index), fuzzy);
                    }
                }
                if (best != null) {
                    decision = new DuplicateDecision(DuplicateKind.FUZZY_CANDIDATE,
                            best.item().sourcePath(), best.item().sourceEntry(), best.match().score(), fuzzyReason(best.match()));
                }
            }

            OrganizerAnalysisItem updated = item.withDuplicate(decision);
            result.add(updated);
            if (includeFuzzySuggestions) {
                previous.add(item);
                previousFuzzyBooks.add(fuzzyBook);
            }

            if (nonBlank(fileHash)) byBytes.putIfAbsent(fileHash, item);
            if (nonBlank(item.fingerprint().semanticSha256())) {
                bySemantic.putIfAbsent(item.fingerprint().semanticSha256(), item);
            }
            String isbn = normalize(item.identity().isbn());
            if (nonBlank(isbn)) byIsbn.putIfAbsent(isbn, item);
        }
        return List.copyOf(result);
    }

    private Book fuzzyProjection(OrganizerAnalysisItem item) {
        String title = item.identity().title();
        String author = item.identity().firstAuthorDisplayName();
        if (!nonBlank(title) || !nonBlank(author)) return null;
        BookMetadata metadata = BookMetadata.builder()
                .year(item.extractedMetadata().year())
                .build();
        return Book.builder()
                .title(title)
                // Keep the normalized display value intact. The existing detector only consumes getFullName().
                .authors(List.of(new Author("", "", author)))
                .series(item.identity().series())
                .metadata(metadata)
                .file(BookFile.empty())
                .local(false)
                .build();
    }

    private String fuzzyReason(FuzzyDuplicateMatch match) {
        String reasons = match.reasons().stream().map(this::reasonLabel).reduce((left, right) -> left + ", " + right).orElse("");
        return String.format(Locale.ROOT, "Fuzzy similarity %.1f%%: %s; тільки для review",
                match.score() * 100.0, reasons);
    }

    private String reasonLabel(FuzzyDuplicateReason reason) {
        return switch (reason) {
            case ISBN_EXACT -> "ISBN збігається";
            case TITLE_EXACT -> "назва збігається";
            case TITLE_SIMILAR -> "назва схожа";
            case AUTHOR_EXACT -> "автор збігається";
            case AUTHOR_SIMILAR -> "автор схожий";
            case YEAR_MATCH -> "рік збігається";
        };
    }

    private boolean incompatibleKnownLanguages(OrganizerAnalysisItem left, OrganizerAnalysisItem right) {
        String leftLanguage = normalize(left.identity().language());
        String rightLanguage = normalize(right.identity().language());
        return nonBlank(leftLanguage) && nonBlank(rightLanguage) && !leftLanguage.equals(rightLanguage);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record FuzzyCandidate(OrganizerAnalysisItem item, FuzzyDuplicateMatch match) { }
}
