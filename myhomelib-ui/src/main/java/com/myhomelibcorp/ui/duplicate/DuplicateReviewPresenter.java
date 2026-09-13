package com.myhomelibcorp.ui.duplicate;

import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.duplicate.fuzzy.DuplicateReviewSuggestion;
import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateReason;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Converts application suggestions into explicit, user-facing evidence strings. */
@Component
public class DuplicateReviewPresenter {

    public DuplicateReviewPresentation present(List<DuplicateReviewSuggestion> suggestions, BookDto source) {
        BookDto effectiveSource = source != null ? source
                : suggestions == null || suggestions.isEmpty() ? null : suggestions.getFirst().source();
        List<DuplicateReviewRow> rows = suggestions == null ? List.of() : suggestions.stream()
                .map(this::row)
                .toList();
        return new DuplicateReviewPresentation(
                effectiveSource == null ? "" : safe(effectiveSource.getTitle()),
                effectiveSource == null ? "" : safe(effectiveSource.getAuthorsText()),
                effectiveSource == null ? "—" : artifacts(effectiveSource),
                rows);
    }

    public DuplicateReviewRow row(DuplicateReviewSuggestion suggestion) {
        BookDto candidate = suggestion.candidate();
        String score = String.format(Locale.ROOT, "%.1f%%", suggestion.score() * 100.0);
        String reasons = suggestion.reasons().stream().map(this::reason).collect(Collectors.joining("; "));
        return new DuplicateReviewRow(
                safe(candidate.getId()), safe(candidate.getTitle()), safe(candidate.getAuthorsText()),
                candidate.getYear() == null ? "—" : candidate.getYear().toString(),
                safe(candidate.getIsbn()).isBlank() ? "—" : candidate.getIsbn(),
                score, reasons, artifacts(candidate));
    }

    public String artifacts(BookDto book) {
        if (book == null || book.getArtifacts().isEmpty()) return "—";
        return book.getArtifacts().stream().map(this::artifact).collect(Collectors.joining(" · "));
    }

    private String artifact(BookArtifactDto artifact) {
        if (artifact == null) return "";
        String format = safe(artifact.getFormat()).isBlank() ? "FILE" : artifact.getFormat().toUpperCase(Locale.ROOT);
        String state = safe(artifact.getState()).isBlank() ? (artifact.isLocal() ? "AVAILABLE" : "REMOTE_ONLY") : artifact.getState();
        String preferredLocation = safe(artifact.getArchiveEntry());
        if (preferredLocation.isBlank()) preferredLocation = safe(artifact.getFileName());
        return preferredLocation.isBlank()
                ? format + " [" + state + "]"
                : format + " [" + state + "] " + preferredLocation;
    }

    private String reason(FuzzyDuplicateReason reason) {
        return switch (reason) {
            case ISBN_EXACT -> "ISBN збігається";
            case TITLE_EXACT -> "назва збігається";
            case TITLE_SIMILAR -> "схожа назва";
            case AUTHOR_EXACT -> "автор збігається";
            case AUTHOR_SIMILAR -> "схожий автор";
            case YEAR_MATCH -> "рік збігається";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
