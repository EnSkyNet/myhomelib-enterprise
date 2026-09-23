package com.myhomelibcorp.ui.presenter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CoverPresenterLifecycleContractTest {
    @Test
    void coverExtractionIsQueuedUnderCollectionQueryBarrier() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/presenter/CoverPresenter.java"),
                StandardCharsets.UTF_8);
        assertThat(source).contains("submitCollectionScoped(LibraryOperationType.QUERY");
        assertThat(source).doesNotContain("backgroundExecutor.submit(() -> {\n            log.debug(\"Виклик coverExtractor.extractCover");
    }
}
