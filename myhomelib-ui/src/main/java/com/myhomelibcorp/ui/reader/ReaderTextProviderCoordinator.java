package com.myhomelibcorp.ui.reader;

import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.cancelRequest;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.normalizeLanguageCode;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.normalizeOptionalLanguageCode;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.replaceCancellation;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.rootMessage;

import com.myhomelibcorp.application.dictionary.DictionaryEntry;
import com.myhomelibcorp.application.dictionary.DictionaryLookupResult;
import com.myhomelibcorp.application.dictionary.DictionaryLookupService;
import com.myhomelibcorp.application.dictionary.DictionaryProviderDescriptor;
import com.myhomelibcorp.application.dictionary.DictionaryQuery;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderIssue;
import com.myhomelibcorp.application.translation.TranslationLookupResult;
import com.myhomelibcorp.application.translation.TranslationProviderDescriptor;
import com.myhomelibcorp.application.translation.TranslationQuery;
import com.myhomelibcorp.application.translation.TranslationService;
import com.myhomelibcorp.reader.api.ReaderSelection;
import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Owns dictionary and translation interaction for one Reader workspace.
 *
 * <p>The coordinator is prototype-scoped because cancellation tokens are workspace-local mutable
 * state. The controller supplies a short-lived context whose {@code stillCurrent} predicate
 * prevents an async completion from updating a different book opened in the same workspace.</p>
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class ReaderTextProviderCoordinator {

    private final DictionaryLookupService dictionaryLookupService;
    private final TranslationService translationService;
    private final DialogService dialogService;
    private final LocalizationService i18n;
    private final ApplicationState appState;

    private final AtomicReference<AtomicBoolean> dictionaryCancellation = new AtomicReference<>();
    private final AtomicReference<AtomicBoolean> translationCancellation = new AtomicReference<>();

    public void lookupDictionary(Context context, ReaderSelection selection) {
        if (!usable(context, selection)) return;
        String term = selection.text().strip();
        if (term.length() > DictionaryQuery.MAX_TERM_LENGTH) {
            dialogService.showWarning(i18n.text("ui.reader.dictionary.title"),
                    i18n.format("ui.reader.dictionary.selection_too_long", DictionaryQuery.MAX_TERM_LENGTH));
            return;
        }

        List<DictionaryProviderDescriptor> providers = dictionaryLookupService.availableProviders();
        if (providers.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.dictionary.title"),
                    i18n.text("ui.reader.text_provider.none.dictionary"));
            return;
        }
        List<DictionaryProviderChoice> choices = providers.stream().map(DictionaryProviderChoice::new).toList();
        Optional<DictionaryProviderChoice> selected = dialogService.showChoiceDialog(
                choices, choices.getFirst(), i18n.text("ui.reader.dictionary.title"),
                i18n.text("ui.reader.dictionary.provider.header"), i18n.text("ui.reader.dictionary.provider"),
                choice -> choice.descriptor().displayName());
        if (selected.isEmpty() || !context.current()) return;

        AtomicBoolean cancellation = replaceCancellation(dictionaryCancellation);
        String language = context.sourceLanguage() == null ? "" : context.sourceLanguage();
        DictionaryQuery query = new DictionaryQuery(term, language, DictionaryQuery.DEFAULT_LIMIT);
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.dictionary.lookup"));
        DictionaryProviderDescriptor provider = selected.get().descriptor();
        dictionaryLookupService.lookup(query, provider.id(), DictionaryLookupService.DEFAULT_TIMEOUT, cancellation)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (!dictionaryCancellation.compareAndSet(cancellation, null) || !context.current()) return;
                    showDictionaryResult(result, term);
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        if (!dictionaryCancellation.compareAndSet(cancellation, null) || !context.current()) return;
                        log.warn("Dictionary lookup failed for book {}: {}", context.bookId(), rootMessage(error));
                        dialogService.showError(i18n.text("ui.reader.dictionary.title"),
                                i18n.format("ui.reader.text_provider.issue.failed", provider.displayName()));
                    });
                    return null;
                });
    }

    public void translate(Context context, ReaderSelection selection) {
        if (!usable(context, selection)) return;
        String text = selection.text().strip();
        if (text.length() > TranslationQuery.MAX_TEXT_LENGTH) {
            dialogService.showWarning(i18n.text("ui.reader.translation.title"),
                    i18n.format("ui.reader.translation.selection_too_long", TranslationQuery.MAX_TEXT_LENGTH));
            return;
        }

        List<TranslationProviderDescriptor> providers = translationService.availableProviders();
        if (providers.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.translation.title"),
                    i18n.text("ui.reader.text_provider.none.translation"));
            return;
        }
        List<TranslationProviderChoice> providerChoices = providers.stream().map(TranslationProviderChoice::new).toList();
        Optional<TranslationProviderChoice> selectedProvider = dialogService.showChoiceDialog(
                providerChoices, providerChoices.getFirst(), i18n.text("ui.reader.translation.title"),
                i18n.text("ui.reader.translation.provider.header"), i18n.text("ui.reader.translation.provider"),
                choice -> choice.descriptor().displayName());
        if (selectedProvider.isEmpty() || !context.current()) return;

        TranslationProviderDescriptor provider = selectedProvider.get().descriptor();
        List<TranslationLanguageChoice> languages = translationLanguages();
        String sourceLanguage = normalizeOptionalLanguageCode(context.sourceLanguage());
        String preferredTarget = normalizeLanguageCode(i18n.language());
        if (!sourceLanguage.isBlank() && sourceLanguage.equals(preferredTarget)) {
            preferredTarget = "en".equals(sourceLanguage) ? "uk" : "en";
        }
        String defaultTarget = preferredTarget;
        TranslationLanguageChoice defaultLanguage = languages.stream()
                .filter(choice -> choice.code().equals(defaultTarget))
                .findFirst().orElse(languages.getFirst());
        Optional<TranslationLanguageChoice> target = dialogService.showChoiceDialog(
                languages, defaultLanguage, i18n.text("ui.reader.translation.title"),
                i18n.text("ui.reader.translation.language.header"), i18n.text("ui.reader.translation.target_language"),
                TranslationLanguageChoice::label);
        if (target.isEmpty() || !context.current()) return;

        if (provider.remote() && !dialogService.showConfirmation(
                i18n.text("ui.reader.translation.privacy.title"),
                i18n.text("ui.reader.translation.privacy.header"),
                i18n.format("ui.reader.translation.privacy.message", provider.displayName()))) {
            return;
        }
        if (!context.current()) return;

        AtomicBoolean cancellation = replaceCancellation(translationCancellation);
        TranslationQuery query = sourceLanguage.isBlank() || sourceLanguage.equals(target.get().code())
                ? TranslationQuery.autoDetect(text, target.get().code())
                : new TranslationQuery(text, sourceLanguage, target.get().code());
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.translation.translating"));
        translationService.translate(query, provider.id(), TranslationService.DEFAULT_TIMEOUT, cancellation)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (!translationCancellation.compareAndSet(cancellation, null) || !context.current()) return;
                    showTranslationResult(result, text, provider.displayName());
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        if (!translationCancellation.compareAndSet(cancellation, null) || !context.current()) return;
                        log.warn("Translation failed for book {}: {}", context.bookId(), rootMessage(error));
                        dialogService.showError(i18n.text("ui.reader.translation.title"),
                                i18n.format("ui.reader.text_provider.issue.failed", provider.displayName()));
                    });
                    return null;
                });
    }

    public void cancelRequests() {
        cancelRequest(dictionaryCancellation);
        cancelRequest(translationCancellation);
    }

    private void showDictionaryResult(DictionaryLookupResult result, String term) {
        if (result == null) {
            dialogService.showError(i18n.text("ui.reader.dictionary.title"),
                    i18n.text("ui.reader.text_provider.issue.failed"));
            return;
        }
        if (result.cancelled()) return;
        if (result.issue() != null) {
            if (result.issue().kind() == TextProviderErrorKind.NOT_FOUND) {
                dialogService.showInfo(i18n.text("ui.reader.dictionary.title"),
                        i18n.format("ui.reader.dictionary.empty", term));
            } else {
                showTextProviderIssue(i18n.text("ui.reader.dictionary.title"), result.issue());
            }
            return;
        }
        if (result.entries().isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.dictionary.title"),
                    i18n.format("ui.reader.dictionary.empty", term));
            return;
        }
        StringBuilder body = new StringBuilder();
        for (DictionaryEntry entry : result.entries()) {
            if (!body.isEmpty()) body.append("\n\n");
            body.append(entry.headword());
            if (!entry.partOfSpeech().isBlank()) body.append(" — ").append(entry.partOfSpeech());
            body.append("\n").append(entry.definition());
            for (String example : entry.examples()) body.append("\n• ").append(example);
            body.append("\n").append(i18n.format("ui.reader.dictionary.source", entry.providerName()));
        }
        showReadOnlyTextDialog(i18n.text("ui.reader.dictionary.title"), term, body.toString());
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.dictionary.ready"));
    }

    private void showTranslationResult(TranslationLookupResult result, String sourceText, String providerName) {
        if (result == null) {
            dialogService.showError(i18n.text("ui.reader.translation.title"),
                    i18n.text("ui.reader.text_provider.issue.failed"));
            return;
        }
        if (result.cancelled()) return;
        if (result.issue() != null) {
            if (result.issue().kind() == TextProviderErrorKind.NOT_FOUND) {
                dialogService.showInfo(i18n.text("ui.reader.translation.title"),
                        i18n.text("ui.reader.translation.empty"));
            } else {
                showTextProviderIssue(i18n.text("ui.reader.translation.title"), result.issue());
            }
            return;
        }
        if (result.translation() == null) {
            dialogService.showInfo(i18n.text("ui.reader.translation.title"), i18n.text("ui.reader.translation.empty"));
            return;
        }
        String body = result.translation().translatedText()
                + "\n\n" + i18n.format("ui.reader.translation.source", providerName);
        showReadOnlyTextDialog(i18n.text("ui.reader.translation.title"), sourceText, body);
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.translation.ready"));
    }

    private void showTextProviderIssue(String title, TextProviderIssue issue) {
        if (issue == null || issue.kind() == TextProviderErrorKind.CANCELLED) return;
        String key = switch (issue.kind()) {
            case TIMEOUT -> "ui.reader.text_provider.issue.timeout";
            case RATE_LIMITED -> "ui.reader.text_provider.issue.rate_limited";
            case UNAVAILABLE -> "ui.reader.text_provider.issue.unavailable";
            case AUTHENTICATION -> "ui.reader.text_provider.issue.authentication";
            case NOT_FOUND -> "ui.reader.text_provider.issue.not_found";
            case INVALID_RESPONSE -> "ui.reader.text_provider.issue.invalid_response";
            case FAILED -> "ui.reader.text_provider.issue.failed";
            case CANCELLED -> "ui.reader.text_provider.issue.cancelled";
        };
        dialogService.showError(title, i18n.format(key, issue.providerName()));
    }

    private void showReadOnlyTextDialog(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogLocalization.prepare(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        TextArea area = new TextArea(content == null ? "" : content);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(64);
        area.setPrefRowCount(18);
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    private List<TranslationLanguageChoice> translationLanguages() {
        return List.of(
                languageChoice("uk"), languageChoice("en"), languageChoice("bg"), languageChoice("de"),
                languageChoice("fr"), languageChoice("es"), languageChoice("pl"), languageChoice("cs"),
                languageChoice("it"), languageChoice("pt"));
    }

    private TranslationLanguageChoice languageChoice(String code) {
        return new TranslationLanguageChoice(code, i18n.text("ui.reader.translation.language." + code));
    }

    private static boolean usable(Context context, ReaderSelection selection) {
        return context != null && context.current()
                && selection != null && selection.text() != null && !selection.text().isBlank();
    }

    public record Context(String bookId, String sourceLanguage, BooleanSupplier stillCurrent) {
        public Context {
            bookId = bookId == null ? "" : bookId;
            sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
        }

        boolean current() {
            return stillCurrent != null && stillCurrent.getAsBoolean();
        }
    }

    private record DictionaryProviderChoice(DictionaryProviderDescriptor descriptor) {
        @Override public String toString() { return descriptor.displayName(); }
    }

    private record TranslationProviderChoice(TranslationProviderDescriptor descriptor) {
        @Override public String toString() { return descriptor.displayName(); }
    }

    private record TranslationLanguageChoice(String code, String label) {
        @Override public String toString() { return label; }
    }
}
