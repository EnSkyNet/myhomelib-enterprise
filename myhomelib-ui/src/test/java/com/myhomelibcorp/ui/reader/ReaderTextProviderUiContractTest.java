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
    void workspaceDelegatesTextProviderWorkAndCancelsItOnBookLifecycle() throws Exception {
        String workspace = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/reader/ReaderTextProviderCoordinator.java"));

        assertThat(workspace).contains(
                "setOnDictionaryRequested(this::lookupDictionaryFromSelection)",
                "setOnTranslationRequested(this::translateSelection)",
                "textProviderCoordinator.lookupDictionary(textProviderContext(), selection)",
                "textProviderCoordinator.translate(textProviderContext(), selection)",
                "textProviderCoordinator.cancelRequests();");
        assertThat(workspace).doesNotContain(
                "DictionaryLookupService dictionaryLookupService",
                "TranslationService translationService",
                "dictionaryCancellation",
                "translationCancellation");

        assertThat(coordinator).contains(
                "@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)",
                "provider.remote() && !dialogService.showConfirmation",
                "ui.reader.translation.privacy.message",
                "translationService.translate(query, provider.id()",
                "dictionaryCancellation.compareAndSet(cancellation, null)",
                "translationCancellation.compareAndSet(cancellation, null)",
                "context.current()");
        assertThat(coordinator).doesNotContain(
                "com.myhomelibcorp.infrastructure.dictionary",
                "com.myhomelibcorp.infrastructure.translation");
    }

    @Test
    void translationIsNeverTriggeredBySelectionChangeObserver() throws Exception {
        String workspace = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));

        assertThat(workspace).doesNotContain("setOnSelectionChanged(this::translate");
        assertThat(workspace).contains("setOnTranslationRequested(this::translateSelection)");
    }
}
