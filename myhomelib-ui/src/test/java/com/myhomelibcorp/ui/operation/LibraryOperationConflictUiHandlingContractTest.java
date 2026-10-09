package com.myhomelibcorp.ui.operation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryOperationConflictUiHandlingContractTest {

    @Test
    void everyCancellableCollectionScopedUiCallerHandlesSynchronousLibraryConflict() throws Exception {
        Path ui = Path.of("src/main/java/com/myhomelibcorp/ui");
        String book = Files.readString(ui.resolve("book/BookWorkspaceController.java"));
        String reader = Files.readString(ui.resolve("reader/NewReaderWorkspaceController.java"));
        String search = Files.readString(ui.resolve("search/ContentSearchCoordinator.java"));

        assertThat(book).contains(
                "submitCollectionScopedCancellable",
                "catch (LibraryOperationConflictException | RejectedExecutionException");
        assertThat(reader).contains(
                "submitCollectionScopedCancellable",
                "catch (com.myhomelibcorp.application.operation.LibraryOperationConflictException busy)");
        assertThat(search).contains(
                "submitCollectionScopedCancellable",
                "catch (LibraryOperationConflictException | RejectedExecutionException");
    }
}
