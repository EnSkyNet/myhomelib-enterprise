package com.myhomelibcorp.application.bulkedit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchMetadataEditEngineTest {
    private static BatchMetadataEditableSnapshot source() {
        return new BatchMetadataEditableSnapshot(
                "  old title  ", "series", 2, "uk", 2020, "Old Publisher",
                "tag-one,tag-two", "Some text 123", List.of(new BatchMetadataGenreSnapshot("sf", "SF", null, "sf")));
    }

    @Test
    void appliesSequentialTextRulesDeterministically() {
        var plan = BatchMetadataEditEngine.prepare(List.of(
                new BatchMetadataEditRule(BatchMetadataEditField.TITLE, BatchMetadataEditAction.TRIM, null, null, null),
                new BatchMetadataEditRule(BatchMetadataEditField.TITLE, BatchMetadataEditAction.REGEX_REPLACE, null, "old", "new"),
                new BatchMetadataEditRule(BatchMetadataEditField.TITLE, BatchMetadataEditAction.CAPITALIZE, null, null, null)));

        var result = plan.apply(source());

        assertEquals("New Title", result.title());
        assertEquals("series", result.series());
        assertEquals(2020, result.year());
    }

    @Test
    void setAndClearStructuredFields() {
        var plan = BatchMetadataEditEngine.prepare(List.of(
                new BatchMetadataEditRule(BatchMetadataEditField.LANGUAGE, BatchMetadataEditAction.SET, "EN", null, null),
                new BatchMetadataEditRule(BatchMetadataEditField.YEAR, BatchMetadataEditAction.CLEAR, null, null, null),
                new BatchMetadataEditRule(BatchMetadataEditField.GENRES, BatchMetadataEditAction.SET, "fantasy, sf, fantasy", null, null)));

        var result = plan.apply(source());

        assertEquals("en", result.language());
        assertNull(result.year());
        assertEquals(List.of("fantasy", "sf"), result.genres().stream().map(BatchMetadataGenreSnapshot::code).toList());
    }

    @Test
    void rejectsBlankTitleAndInvalidRegexBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> BatchMetadataEditEngine.prepare(List.of(
                new BatchMetadataEditRule(BatchMetadataEditField.TITLE, BatchMetadataEditAction.CLEAR, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> BatchMetadataEditEngine.prepare(List.of(
                new BatchMetadataEditRule(BatchMetadataEditField.TITLE, BatchMetadataEditAction.REGEX_REPLACE, null, "[", "x"))));
    }

    @Test
    void changedFieldsReportsOnlyMutatedProperties() {
        var after = BatchMetadataEditEngine.prepare(List.of(
                new BatchMetadataEditRule(BatchMetadataEditField.PUBLISHER, BatchMetadataEditAction.SET, "New Publisher", null, null)))
                .apply(source());

        assertEquals(List.of(BatchMetadataEditField.PUBLISHER), BatchMetadataEditEngine.changedFields(source(), after));
    }
}
