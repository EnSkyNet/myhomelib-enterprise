package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.domain.model.book.Book;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure Current-vs-Proposed projection for single-book and batch review. */
@Service
public class MetadataMergePreviewService {

    public MetadataMergePreview preview(Book book, MetadataCandidate candidate) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(candidate, "candidate");
        List<MetadataFieldComparison> rows = new ArrayList<>();
        rows.add(row(MetadataMergeField.TITLE, book.getTitle(), candidate.title()));
        rows.add(row(MetadataMergeField.AUTHORS, book.authorsText(), String.join("; ", candidate.authors())));
        rows.add(row(MetadataMergeField.ISBN,
                book.getIsbn() == null ? "" : book.getIsbn().value(), candidate.isbn()));
        rows.add(row(MetadataMergeField.YEAR,
                book.getYear() == null ? "" : book.getYear().toString(),
                candidate.year() == null ? "" : candidate.year().toString()));
        rows.add(row(MetadataMergeField.PUBLISHER, book.getPublisher(), candidate.publisher()));
        rows.add(row(MetadataMergeField.LANGUAGE,
                book.getLanguage() == null ? "" : book.getLanguage().value(), candidate.language()));
        rows.add(row(MetadataMergeField.ANNOTATION, book.getAnnotation(), candidate.annotation()));
        return new MetadataMergePreview(book.getId(), book.getTitle(), candidate, rows);
    }

    public List<MetadataMergePreview> previewBatch(List<PreviewRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        List<MetadataMergePreview> result = new ArrayList<>(requests.size());
        for (PreviewRequest request : requests) {
            if (request == null) continue;
            result.add(preview(request.book(), request.candidate()));
        }
        return List.copyOf(result);
    }

    private static MetadataFieldComparison row(MetadataMergeField field, String currentValue, String proposedValue) {
        String current = clean(currentValue);
        String proposed = clean(proposedValue);
        return new MetadataFieldComparison(field, current, proposed, !current.equals(proposed));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record PreviewRequest(Book book, MetadataCandidate candidate) {
        public PreviewRequest {
            Objects.requireNonNull(book, "book");
            Objects.requireNonNull(candidate, "candidate");
        }
    }
}
