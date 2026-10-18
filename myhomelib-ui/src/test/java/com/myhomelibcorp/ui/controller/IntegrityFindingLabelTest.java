package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.integrity.ArtifactIntegrityFinding;
import com.myhomelibcorp.application.integrity.ArtifactIntegrityStatus;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrityFindingLabelTest {
    @Test
    void readerSeesTitleAndFilenameButNeverTechnicalIdentifierOrAbsolutePath() {
        var finding = new ArtifactIntegrityFinding("artifact-uuid-123", "book-uuid-456", "Назва книги",
                "D:\\Flib\\sensitive\\book.epub", ArtifactIntegrityStatus.MISSING,
                4, 0, "secret-expected-hash", "secret-observed-hash", "raw error", Instant.now());
        String text = IntegrityCheckController.userFacingFindingLabel(finding);
        assertThat(text).contains("Назва книги", "book.epub")
                .doesNotContain("artifact-uuid", "book-uuid", "D:", "sensitive", "secret", "hash");
    }

    @Test
    void missingTitleDoesNotFallBackToUuid() {
        var finding = new ArtifactIntegrityFinding("artifact-123", "book-456", "", "book.fb2",
                ArtifactIntegrityStatus.UNREADABLE, 0, 0, "", "", "", Instant.now());
        assertThat(IntegrityCheckController.userFacingFindingLabel(finding))
                .contains("book.fb2").doesNotContain("artifact-123", "book-456");
    }
}
