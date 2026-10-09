package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dictionary.DictionaryLookupResult;
import com.myhomelibcorp.application.dictionary.DictionaryLookupService;
import com.myhomelibcorp.application.dictionary.DictionaryProviderDescriptor;
import com.myhomelibcorp.application.translation.TranslationService;
import com.myhomelibcorp.reader.api.ReaderSelection;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReaderTextProviderCoordinatorTest {

    @Test
    void oversizedDictionarySelectionIsRejectedBeforeProviderDiscovery() {
        DictionaryLookupService dictionary = mock(DictionaryLookupService.class);
        DialogService dialogs = mock(DialogService.class);
        ReaderTextProviderCoordinator coordinator = coordinator(dictionary, mock(TranslationService.class), dialogs);
        String text = "x".repeat(257);

        coordinator.lookupDictionary(currentContext(), selection(text));

        verifyNoInteractions(dictionary);
        verify(dialogs).showWarning(any(), any());
    }

    @Test
    void staleContextDoesNotReachProvider() {
        DictionaryLookupService dictionary = mock(DictionaryLookupService.class);
        ReaderTextProviderCoordinator coordinator = coordinator(
                dictionary, mock(TranslationService.class), mock(DialogService.class));

        coordinator.lookupDictionary(
                new ReaderTextProviderCoordinator.Context("book", "uk", () -> false),
                selection("term"));

        verifyNoInteractions(dictionary);
    }

    @Test
    void cancelRequestsCancelsActiveDictionaryToken() {
        DictionaryLookupService dictionary = mock(DictionaryLookupService.class);
        DialogService dialogs = mock(DialogService.class);
        ReaderTextProviderCoordinator coordinator = coordinator(dictionary, mock(TranslationService.class), dialogs);
        DictionaryProviderDescriptor provider = new DictionaryProviderDescriptor("dict", "Dictionary", true);
        when(dictionary.availableProviders()).thenReturn(List.of(provider));
        doAnswer(invocation -> Optional.of(((List<?>) invocation.getArgument(0)).getFirst()))
                .when(dialogs).showChoiceDialog(anyList(), any(), any(), any(), any(), any());

        AtomicReference<AtomicBoolean> observedToken = new AtomicReference<>();
        when(dictionary.lookup(any(), eq("dict"), any(Duration.class), any(AtomicBoolean.class)))
                .thenAnswer(invocation -> {
                    observedToken.set(invocation.getArgument(3));
                    return new CompletableFuture<DictionaryLookupResult>();
                });

        coordinator.lookupDictionary(currentContext(), selection("term"));
        assertThat(observedToken.get()).isNotNull();
        assertThat(observedToken.get()).isFalse();

        coordinator.cancelRequests();

        assertThat(observedToken.get()).isTrue();
        verify(dictionary, never()).lookup(any(), eq("other"), any(Duration.class), any(AtomicBoolean.class));
    }

    private static ReaderTextProviderCoordinator coordinator(
            DictionaryLookupService dictionary,
            TranslationService translation,
            DialogService dialogs) {
        return new ReaderTextProviderCoordinator(
                dictionary,
                translation,
                dialogs,
                mock(LocalizationService.class),
                new ApplicationState());
    }

    private static ReaderTextProviderCoordinator.Context currentContext() {
        return new ReaderTextProviderCoordinator.Context("book", "uk", () -> true);
    }

    private static ReaderSelection selection(String text) {
        return new ReaderSelection(0, text.length(), text, 0, null, null, 0, null, 0.0, "", "");
    }
}
