package com.myhomelibcorp.ui.details;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BookDetailsV33V34ActionContractTest {
    private static String controllerSource() throws Exception {
        Path path = Path.of("src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java");
        String source = Files.readString(path);
        assertThat(source).contains("class BookDetailsController");
        return source;
    }

    private static String fxmlSource() throws Exception {
        return Files.readString(Path.of("src/main/resources/view/details.fxml"));
    }

    @Test
    void similarBookLinksUseStableBookIdentityAndCanonicalNavigation() throws Exception {
        assertThat(controllerSource())
                .contains("findSimilarBooksUseCase.find(BookId.fromString(bookId), 8)")
                .contains("link.setId(\"similar-book-open-\" + item.bookId())")
                .contains("link.setOnAction")
                .contains("navigationService.navigateToBook(BookId.fromString(item.bookId()))");
    }

    @Test
    void exactDuplicatePageScanIsReportOnlyAndUsesApplicationUseCase() throws Exception {
        assertThat(fxmlSource())
                .contains("fx:id=\"scanDuplicatePagesButton\"")
                .contains("onAction=\"#onScanDuplicatePages\"");
        assertThat(controllerSource())
                .contains("private void onScanDuplicatePages()")
                .contains("scanComicDuplicatePagesUseCase.scan(BookId.fromString(bookId), ComicDuplicateScanControl.none())")
                .contains("showDuplicatePagesReport(result)")
                .doesNotContain("deleteDuplicatePage")
                .doesNotContain("removeDuplicatePage");
    }
}
