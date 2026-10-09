package com.myhomelibcorp.ui.presenter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CoverPresenterLifecycleContractTest {
    @Test
    void coverExtractionDoesNotBlockTheGlobalLibraryOperationBarrier() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/presenter/CoverPresenter.java"),
                StandardCharsets.UTF_8);
        assertThat(source).contains("backgroundExecutor.submitInteractiveCancellable(() -> {");
        assertThat(source).doesNotContain("submitCollectionScoped(LibraryOperationType.QUERY");
        assertThat(source).contains("current.cancel()");
        assertThat(source).contains("coverImageView.getImage() != null");
        assertThat(source).contains("currentLoadingRequest.compareAndSet(requestKey, null)");
        assertThat(source).contains("public void showCover(BookDto book)");
        assertThat(source).contains("return coverExtractor.extractCover(book);");
        assertThat(source).contains("fxImage.isError()", "fxImage.getWidth() <= 0", "fxImage.getHeight() <= 0");
        assertThat(source).contains("coverExtractor.invalidateCover(book)");
        assertThat(source).contains("safe(book.getCollectionRoot())", "safe(book.getArchiveEntry())", "safe(book.getPreferredArtifactId())");
        assertThat(source).contains("stage=decode");
        assertThat(source).contains("lastCoverAbsent = true");
        assertThat(source).contains("800, 1200, true, true");
        assertThat(source).doesNotContain("}).whenComplete((ignored, failure) -> {");
    }
}
