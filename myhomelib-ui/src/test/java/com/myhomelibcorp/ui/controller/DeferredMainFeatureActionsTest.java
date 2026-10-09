package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.action.ActionCustomizationDialog;
import com.myhomelibcorp.ui.action.BookActionProfilesDialog;
import com.myhomelibcorp.ui.duplicate.DuplicateReviewUiService;
import com.myhomelibcorp.ui.integration.AiBookAssistantUiService;
import com.myhomelibcorp.ui.integration.IntegrationCenterDialog;
import com.myhomelibcorp.ui.opds.OpdsUiService;
import com.myhomelibcorp.ui.organizer.LibraryOrganizerUiService;
import com.myhomelibcorp.ui.service.ApplicationSettingsDialog;
import com.myhomelibcorp.ui.service.BookConversionUiService;
import com.myhomelibcorp.ui.service.CollectionPropertiesUiService;
import com.myhomelibcorp.ui.service.HelpService;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeferredMainFeatureActionsTest {

    @Test
    void everyOptionalMainFeatureDelegatesToItsLazyService() {
        ActionCustomizationDialog customization = mock(ActionCustomizationDialog.class);
        BookActionProfilesDialog profiles = mock(BookActionProfilesDialog.class);
        OpdsUiService opds = mock(OpdsUiService.class);
        ApplicationSettingsDialog settings = mock(ApplicationSettingsDialog.class);
        IntegrationCenterDialog integrations = mock(IntegrationCenterDialog.class);
        BookConversionUiService conversion = mock(BookConversionUiService.class);
        AiBookAssistantUiService ai = mock(AiBookAssistantUiService.class);
        LibraryOrganizerUiService organizer = mock(LibraryOrganizerUiService.class);
        DuplicateReviewUiService duplicates = mock(DuplicateReviewUiService.class);
        CollectionPropertiesUiService properties = mock(CollectionPropertiesUiService.class);
        HelpService help = mock(HelpService.class);

        DeferredMainFeatureActions actions = new DeferredMainFeatureActions(
                provider(customization), provider(profiles), provider(opds), provider(settings), provider(integrations),
                provider(conversion), provider(ai), provider(organizer), provider(duplicates), provider(properties), provider(help));
        Window owner = mock(Window.class);
        BookDto book = BookDto.builder().id(BookId.generate().toString()).title("Book").build();
        List<BookId> ids = List.of(BookId.generate(), BookId.generate());

        actions.showOpds(owner);
        actions.showSettings(owner);
        actions.showIntegrations(owner);
        actions.showBookConversion(owner, book);
        actions.showBookConversion(owner, book, ids);
        actions.summarizeBook(owner, book);
        actions.askBook(owner, book);
        actions.showLibraryOrganizer(owner);
        actions.showDuplicateReview(owner);
        actions.showActionCustomization(owner);
        actions.showBookActionProfiles(owner);
        actions.showHelp(owner, "reader");

        verify(opds).show(owner);
        verify(settings).show(owner);
        verify(integrations).show(owner);
        verify(conversion).show(owner, book);
        verify(conversion).show(owner, book, ids);
        verify(ai).summarize(owner, book);
        verify(ai).ask(owner, book);
        verify(organizer).show(owner);
        verify(duplicates).show(owner);
        verify(customization).show(owner);
        verify(profiles).show(owner);
        verify(help).show(owner, "reader");
    }

    @Test
    void collectionPropertiesReportsWhetherDialogCommitted() {
        CollectionPropertiesUiService service = mock(CollectionPropertiesUiService.class);
        Window owner = mock(Window.class);
        DeferredMainFeatureActions actions = new DeferredMainFeatureActions(
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), emptyProvider(), provider(service), emptyProvider());

        when(service.show(owner)).thenReturn(mock(Collection.class), (Collection) null);
        assertThat(actions.showCollectionProperties(owner)).isTrue();
        assertThat(actions.showCollectionProperties(owner)).isFalse();
        verify(service, times(2)).show(owner);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        @SuppressWarnings("unchecked") ObjectProvider<T> provider = mock(ObjectProvider.class);
        return provider;
    }
}
