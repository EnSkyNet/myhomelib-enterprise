package com.myhomelibcorp.organizer.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InferredAuthorNameOrderTest {
    private final MetadataNormalizationService normalizer = new MetadataNormalizationService();

    @Test void commonTwoPartGivenFirstIsReordered() {
        assertEquals("Гауз Александр", InferredAuthorNameOrder.fromGivenFirst("Александр Гауз").displayName());
        assertEquals("Gauzs Александр", InferredAuthorNameOrder.fromGivenFirst("Александр Gauzs").displayName());
        assertEquals("Шевченко Олександр", InferredAuthorNameOrder.fromGivenFirst("Олександр Шевченко").displayName());
        assertEquals("Васильева Алёна", InferredAuthorNameOrder.fromGivenFirst("Алёна Васильева").displayName());
        assertEquals("Васильева Алена", InferredAuthorNameOrder.fromGivenFirst("Алена Васильева").displayName());
    }
    @Test void givenPatronymicFamilyReorders() {
        NormalizedAuthor author = InferredAuthorNameOrder.fromGivenFirst("Александр Александрович Бушков");
        assertEquals("Бушков Александр Александрович", author.displayName());
        assertEquals("Бушков", author.familyName());
    }
    @Test void epubCreatorWithoutFileAsUsesConservativeInferenceInRealOrganizerPath() {
        var raw = new ExtractedBookMetadata("Книга", java.util.List.of("Александр Гауз"), "", "", "ru", "", "",
                null, "", java.util.List.of(), false, "epub", com.myhomelibcorp.organizer.detect.DetectionConfidence.CERTAIN,
                java.util.List.of());
        var identity = normalizer.normalize(raw);
        var path = new com.myhomelibcorp.organizer.naming.CanonicalBookPathService().relativePath(identity,
                com.myhomelibcorp.organizer.policy.OrganizerProfile.safeDefault(), "epub");
        assertEquals("Гауз Александр", path.getName(0).toString());
        assertTrue(identity.issues().stream().anyMatch(issue -> issue.code().equals("AUTHOR_ORDER_INFERRED")));
    }

    @Test void alreadySurnameFirstOrAmbiguousRemainsUnchanged() {
        assertNull(InferredAuthorNameOrder.fromGivenFirst("Дорничев Дмитрий"));
        assertEquals("Дорничев Дмитрий", InferredAuthorNameOrder.fromSurnameFirst("Дорничев Дмитрий").displayName());
        assertEquals("Алёхин Глеб Викторович", InferredAuthorNameOrder.fromSurnameFirst("Алёхин Глеб Викторович").displayName());
        assertNull(InferredAuthorNameOrder.fromGivenFirst("Джон Рональд Руэл Толкин"));
        assertNull(InferredAuthorNameOrder.fromGivenFirst("Александр Андрей"));
        assertNull(InferredAuthorNameOrder.fromGivenFirst("Фантастический Автор"));
    }
    @Test void structuredCommaRemainsAuthoritative() {
        assertNull(InferredAuthorNameOrder.fromGivenFirst("Иванов, Иван"));
    }
}
