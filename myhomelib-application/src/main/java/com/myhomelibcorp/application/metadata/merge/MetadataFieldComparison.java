package com.myhomelibcorp.application.metadata.merge;

/** One Current vs Proposed row. Values are presentation-safe text; mutations use the original candidate. */
public record MetadataFieldComparison(
        MetadataMergeField field,
        String currentValue,
        String proposedValue,
        boolean changed) {
    public MetadataFieldComparison {
        if (field == null) throw new IllegalArgumentException("field is required");
        currentValue = clean(currentValue);
        proposedValue = clean(proposedValue);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
