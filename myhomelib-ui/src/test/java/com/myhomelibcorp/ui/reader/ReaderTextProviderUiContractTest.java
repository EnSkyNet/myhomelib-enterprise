package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderTextProviderUiContractTest {
    @Test
    void selectionMenuExposesDictionaryAndTranslationWithoutApplicationDependencyInReaderModule() throws Exception {
        Path readerRoot = Path.of("../myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx");
        String canvas = Files.readString(readerRoot.resolve("ReaderCanvas.java"));
        String view = Files.readString(readerRoot.resolve("ReaderView.java"));

        assertThat(canvas).contains(
                "ui.reader.selection.dictionary",
                "ui.reader.selection.translate",
                "setOnDictionaryRequested",
                "setOnTranslationRequested");
        assertThat(view).contains(
                "setOnDictionaryRequested",
                "setOnTranslationRequested");
        assertThat(canvas).doesNotContain(
                "com.myhomelibcorp.application.dictionary",
                "com.myhomelibcorp.application.translation",
                "com.myhomelibcorp.infrastructure");
    }

    @Test
    void workspaceRequiresExplicitCloudConsentAndCancelsRequestsOnBookLifecycle() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));

        assertThat(source).contains(
                "setOnDictionaryRequested(this::lookupDictionaryFromSelection)",
                "setOnTranslationRequested(this::translateSelection)",
                "provider.remote() && !dialogService.showConfirmation",
                "ui.reader.translation.privacy.message",
                "translationService.translate(query, provider.id()",
                "cancelTextProviderRequests();",
                "dictionaryCancellation.compareAndSet(cancellation, null)",
                "translationCancellation.compareAndSet(cancellation, null)");
        assertThat(source).doesNotContain(
                "com.myhomelibcorp.infrastructure.dictionary",
                "com.myhomelibcorp.infrastructure.translation");
    }

    @Test
    void translationIsNeverTriggeredBySelectionChangeObserver() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));

        assertThat(source).doesNotContain("setOnSelectionChanged(this::translate");
        assertThat(source).contains("setOnTranslationRequested(this::translateSelection)");
    }
}
