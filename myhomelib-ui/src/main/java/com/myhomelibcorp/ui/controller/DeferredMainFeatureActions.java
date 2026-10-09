package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Lazy boundary for optional main-window features.
 *
 * <p>The application shell must not instantiate integrations, converters, OPDS management,
 * duplicate review and other feature-specific graphs until the user actually opens them.
 * Keeping the {@link ObjectProvider providers} behind this coordinator also prevents
 * {@link MainController} from becoming a service locator.</p>
 */
@Component
@RequiredArgsConstructor
public final class DeferredMainFeatureActions {
    private final ObjectProvider<ActionCustomizationDialog> actionCustomizationDialog;
    private final ObjectProvider<BookActionProfilesDialog> bookActionProfilesDialog;
    private final ObjectProvider<OpdsUiService> opdsUiService;
    private final ObjectProvider<ApplicationSettingsDialog> applicationSettingsDialog;
    private final ObjectProvider<IntegrationCenterDialog> integrationCenterDialog;
    private final ObjectProvider<BookConversionUiService> bookConversionUiService;
    private final ObjectProvider<AiBookAssistantUiService> aiBookAssistantUiService;
    private final ObjectProvider<LibraryOrganizerUiService> libraryOrganizerUiService;
    private final ObjectProvider<DuplicateReviewUiService> duplicateReviewUiService;
    private final ObjectProvider<CollectionPropertiesUiService> collectionPropertiesUiService;
    private final ObjectProvider<HelpService> helpService;

    public void showOpds(Window owner) {
        opdsUiService.getObject().show(owner);
    }

    public void showSettings(Window owner) {
        applicationSettingsDialog.getObject().show(owner);
    }

    public void showIntegrations(Window owner) {
        integrationCenterDialog.getObject().show(owner);
    }

    public void showBookConversion(Window owner, BookDto book) {
        bookConversionUiService.getObject().show(owner, book);
    }

    public void showBookConversion(Window owner, BookDto book, java.util.List<com.myhomelibcorp.domain.model.valueobject.BookId> selectedIds) {
        bookConversionUiService.getObject().show(owner, book, selectedIds);
    }

    public void summarizeBook(Window owner, BookDto book) {
        aiBookAssistantUiService.getObject().summarize(owner, book);
    }

    public void askBook(Window owner, BookDto book) {
        aiBookAssistantUiService.getObject().ask(owner, book);
    }

    public boolean showCollectionProperties(Window owner) {
        return collectionPropertiesUiService.getObject().show(owner) != null;
    }

    public void showLibraryOrganizer(Window owner) {
        libraryOrganizerUiService.getObject().show(owner);
    }

    public void showDuplicateReview(Window owner) {
        duplicateReviewUiService.getObject().show(owner);
    }

    public void showActionCustomization(Window owner) {
        actionCustomizationDialog.getObject().show(owner);
    }

    public void showBookActionProfiles(Window owner) {
        bookActionProfilesDialog.getObject().show(owner);
    }

    public void showHelp(Window owner, String topic) {
        helpService.getObject().show(owner, topic);
    }
}
