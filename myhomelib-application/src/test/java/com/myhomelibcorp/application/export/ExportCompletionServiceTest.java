package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportCompletionServiceTest {
    @TempDir Path temp;
    private final ExportCompletionService service = new ExportCompletionService();

    @Test
    void verifyReadablePolicyDoesNotRequireAFileFlush() {
        assertThatCode(() -> service.complete(temp.resolve("missing.fb2"), ExportRequest.CompletionPolicy.VERIFY_READABLE))
                .doesNotThrowAnyException();
    }

    @Test
    void ejectSafePolicyFlushesExistingRegularFile() throws Exception {
        Path file = temp.resolve("book.fb2");
        Files.writeString(file, "book", StandardCharsets.UTF_8);

        assertThatCode(() -> service.complete(file, ExportRequest.CompletionPolicy.EJECT_SAFE))
                .doesNotThrowAnyException();
    }

    @Test
    void ejectSafePolicyFailsClosedWhenCommittedFileIsMissing() {
        Path missing = temp.resolve("missing.fb2");
        assertThatThrownBy(() -> service.complete(missing, ExportRequest.CompletionPolicy.EJECT_SAFE))
                .isInstanceOf(IOException.class);
    }
}
