package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderWorkspaceDetailsReuseContractTest {

    @Test
    void readerTransitionDoesNotRetriggerRichDetailsForSameLogicalBook() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));

        assertThat(source).contains("setCurrentBookIfDifferentId(currentBook)");
        assertThat(source).doesNotContain("getBookDetails().setCurrentBook(currentBook)");
    }
}
