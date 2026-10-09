package com.myhomelibcorp.ui.details;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BookDetailsSelectionNonBlockingContractTest {

    @Test
    void passiveSelectionShowsBasicMetadataImmediatelyAndDoesNotAcquireQueryBarrier() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java"),
                StandardCharsets.UTF_8);

        assertThat(source).contains(
                "updateBasicUI(book);",
                "backgroundExecutor.submitInteractiveCancellable(() -> analysisService.analyze(selected.getId()))",
                "cancelDetailsAnalysis();",
                "cancelSimilarBooks();",
                "bookDownloadCoordinator.reconcileLocalClaim(selected);",
                "backgroundExecutor.submitInteractiveCancellable(() -> findSimilarBooksUseCase.find(BookId.fromString(bookId), 8))");
        assertThat(source).doesNotContain(
                "LibraryOperationType.QUERY, () -> analysisService.analyze(selected.getId())",
                "LibraryOperationType.QUERY,\n                        () -> findSimilarBooksUseCase.find(BookId.fromString(bookId), 8)");
    }

    @Test
    void remoteOnlySelectionIsNormalStateAndReturnsBeforeFileInspection() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java"),
                StandardCharsets.UTF_8);
        String fxml = Files.readString(Path.of("src/main/resources/view/details.fxml"), StandardCharsets.UTF_8);

        int remoteBranch = source.indexOf("if (!hasLocalArtifactClaim(selected))");
        int analysis = source.indexOf("analysisService.analyze(selected.getId())");
        assertThat(remoteBranch).isGreaterThanOrEqualTo(0).isLessThan(analysis);
        assertThat(source.substring(remoteBranch, analysis)).contains(
                "showWarning(UiText.text(\"ui.book_details.not_downloaded\"))",
                "loadSimilarBooks(selected.getId(), requestToken);",
                "return;");
        assertThat(fxml).contains("fx:id=\"downloadBookButton\"", "onAction=\"#onDownloadBook\"");
        assertThat(source).contains("bookDownloadCoordinator.canDownload(book)");
        int localClaimStart = source.indexOf("private static boolean hasLocalArtifactClaim");
        int remoteHelperStart = source.indexOf("private void updateDownloadAction", localClaimStart);
        assertThat(localClaimStart).isGreaterThanOrEqualTo(0);
        assertThat(remoteHelperStart).isGreaterThan(localClaimStart);
        String localClaimHelper = source.substring(localClaimStart, remoteHelperStart);
        assertThat(localClaimHelper).contains(
                "if (book.isLocal()) return true;",
                "artifact != null && artifact.isLocal()");
        assertThat(localClaimHelper).doesNotContain("\"AVAILABLE\".equalsIgnoreCase(artifact.getState())");
    }
}
