package com.myhomelibcorp.ui.metadata;

import com.myhomelibcorp.application.metadata.merge.MetadataFieldComparison;
import com.myhomelibcorp.application.metadata.merge.MetadataMergeField;
import com.myhomelibcorp.application.metadata.merge.MetadataMergePreview;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Presentation-only projection for Current vs Proposed metadata review. */
@Component
public class MetadataMergePresenter {
    public List<Row> present(List<MetadataMergePreview> previews) {
        if (previews == null || previews.isEmpty()) return List.of();
        List<Row> rows = new ArrayList<>();
        for (MetadataMergePreview preview : previews) {
            if (preview == null) continue;
            String confidence = String.format(Locale.ROOT, "%.1f%%", preview.confidence() * 100.0);
            String source = preview.source().providerName();
            for (MetadataFieldComparison field : preview.fields()) {
                rows.add(new Row(preview.bookId().toString(), preview.bookTitle(), field.field(), label(field.field()),
                        field.currentValue(), field.proposedValue(), field.changed(), confidence, source));
            }
        }
        return List.copyOf(rows);
    }

    private static String label(MetadataMergeField field) {
        return switch (field) {
            case TITLE -> "Назва";
            case AUTHORS -> "Автори";
            case ISBN -> "ISBN";
            case YEAR -> "Рік";
            case PUBLISHER -> "Видавець";
            case LANGUAGE -> "Мова";
            case ANNOTATION -> "Анотація";
        };
    }

    public record Row(String bookId, String bookTitle, MetadataMergeField field, String fieldLabel,
                      String currentValue, String proposedValue, boolean changed,
                      String confidence, String source) {
    }
}
