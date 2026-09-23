package com.myhomelibcorp.ui.duplicate;


import com.myhomelibcorp.shared.util.FileNameSupport;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.duplicate.fuzzy.DuplicateReviewSuggestion;
import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateReason;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.util.ArtifactStateText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Converts application suggestions into explicit, user-facing evidence strings. */
@Component
@RequiredArgsConstructor
public class DuplicateReviewPresenter {

    private final LocalizationService localizationService;

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
        String rawFormat = safe(artifact.getFormat());
        String format = rawFormat.isBlank() ? UiText.text("common.file") : rawFormat.toUpperCase(Locale.ROOT);
        String state = localizationService.tr(ArtifactStateText.sourceLabel(artifact.getState(), artifact.isLocal()));
        String preferredLocation = safe(artifact.getArchiveEntry());
        if (preferredLocation.isBlank()) preferredLocation = safe(artifact.getFileName());
        preferredLocation = FileNameSupport.decodeHashUnicodeEscapes(preferredLocation);
        return preferredLocation.isBlank()
                ? format + " [" + state + "]"
                : format + " [" + state + "] " + preferredLocation;
    }

    private String reason(FuzzyDuplicateReason reason) {
        return switch (reason) {
            case ISBN_EXACT -> UiText.text("ui.java.duplicate_review_presenter.fad9ef619f");
            case TITLE_EXACT -> UiText.text("ui.java.duplicate_review_presenter.954e9cd2a2");
            case TITLE_SIMILAR -> UiText.text("ui.java.duplicate_review_presenter.720b0ca404");
            case AUTHOR_EXACT -> UiText.text("ui.java.duplicate_review_presenter.4308744e56");
            case AUTHOR_SIMILAR -> UiText.text("ui.java.duplicate_review_presenter.6c81a7b0fa");
            case YEAR_MATCH -> UiText.text("ui.java.duplicate_review_presenter.0c600d2ba7");
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
