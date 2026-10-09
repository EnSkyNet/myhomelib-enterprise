package com.myhomelibcorp.ui.author;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorWorkspaceLatestRequestContractTest {
    @Test
    void authorWorkspaceCancelsSupersededQueriesInsteadOfQueueingThem() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/author/AuthorWorkspaceController.java"),
                StandardCharsets.UTF_8);
        assertThat(source).contains(
                "cancelMetadataTask();",
                "cancelBooksTask();",
                "submitCollectionScopedInteractiveCancellable(LibraryOperationType.QUERY",
                "UiExceptionSupport.isCancellation(ex)");
        assertThat(source).doesNotContain(
                "executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> {\n            AuthorDto author",
                "executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> {\n            List<BookListItem> items");
    }
}
