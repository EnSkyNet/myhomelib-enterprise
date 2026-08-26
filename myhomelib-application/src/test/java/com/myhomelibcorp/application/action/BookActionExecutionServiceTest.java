package com.myhomelibcorp.application.action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookActionExecutionServiceTest {
    private final BookActionExecutionService service = new BookActionExecutionService();

    @Test
    void previewKeepsMetadataInsideOriginalArgumentBoundary() {
        BookActionProfile profile = new BookActionProfile("p", "Preview", true, List.of(
                new BookActionCommand("tool", "--title \"%TITLE%\" --file \"%FILE%\"", "%DIR%", false)));

        BookActionPreview preview = service.preview(profile, Map.of(
                "%TITLE%", "Book ; rm -rf / --still-one-arg",
                "%FILE%", "C:\\A B\\book.fb2",
                "%DIR%", "C:\\A B"));

        assertThat(preview.commands().getFirst().argv()).containsExactly(
                "tool", "--title", "Book ; rm -rf / --still-one-arg", "--file", "C:\\A B\\book.fb2");
        assertThat(preview.commands().getFirst().workingDirectory()).isEqualTo("C:\\A B");
    }
}
