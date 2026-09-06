package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClassicEditAsyncContractTest {
    @Test
    void classicEditUsesApplicationUseCaseAndNeverWritesRepositoryOrLuceneDirectly() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/service/ClassicLibraryActionsService.java"), StandardCharsets.UTF_8);
        assertThat(source).contains("EditBookUseCase", "backgroundExecutor.submit(() -> editBookUseCase.execute(request))");
        assertThat(source).doesNotContain("BookCommandRepository", "SearchIndexer", "commands.save(", "indexer.indexBook(", "indexer.commit(");
    }

    @Test
    void initialEditSnapshotIsAlsoLoadedOffFxThread() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/service/ClassicLibraryActionsService.java"), StandardCharsets.UTF_8);
        assertThat(source).contains("backgroundExecutor.submit(() -> query.findById(id).orElse(null))");
        assertThat(source.indexOf("backgroundExecutor.submit(() -> query.findById(id).orElse(null))"))
                .isLessThan(source.indexOf("showEditDialog(owner, book, onSuccess)"));
    }
}
