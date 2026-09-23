package com.myhomelibcorp.ui.integration;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.content.ContentExtractionSource;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Explicit, consent-gated AI actions for the currently selected book. */
@Slf4j
@Component
public final class AiBookAssistantUiService {
    private final IntegrationBackend backend;
    private final ResolveBookContentUseCase resolveBookContent;
    private final ContentExtractionService extraction;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;

    public AiBookAssistantUiService(IntegrationBackend backend,
                                    ResolveBookContentUseCase resolveBookContent,
                                    ContentExtractionService extraction,
                                    UiBackgroundExecutor background,
                                    DialogService dialogs) {
        this.backend = backend;
        this.resolveBookContent = resolveBookContent;
        this.extraction = extraction;
        this.background = background;
        this.dialogs = dialogs;
    }

    public void summarize(Window owner, BookDto book) {
        run(owner, book, AiOperation.SUMMARY,
                UiText.text("ui.java.ai_book_assistant_ui_service.beaafbf241"));
    }

    public void ask(Window owner, BookDto book) {
        if (book == null) { noBook(); return; }
        Optional<String> prompt = dialogs.showTextInput(UiText.text("ui.java.ai_book_assistant_ui_service.188393e6e5"), UiText.text("ui.java.ai_book_assistant_ui_service.7db4f1f9be"),
                UiText.text("ui.java.ai_book_assistant_ui_service.62e15bb30b"), "");
        if (prompt.isEmpty() || prompt.get().isBlank()) return;
        run(owner, book, AiOperation.QUESTION_ANSWER, prompt.get());
    }

    private void run(Window owner, BookDto book, AiOperation operation, String prompt) {
        if (book == null) { noBook(); return; }
        List<IntegrationBackend.AiProviderInfo> available = backend.aiProviders().stream()
                .filter(provider -> provider.operations().contains(operation.name()))
                .toList();
        if (available.isEmpty()) {
            dialogs.showWarning(UiText.text("ui.java.ai_book_assistant_ui_service.fdc40cc5d5"), UiText.text("ui.java.ai_book_assistant_ui_service.64be13955c"));
            return;
        }
        ProviderChoice defaultChoice = new ProviderChoice(available.getFirst());
        List<ProviderChoice> choices = available.stream().map(ProviderChoice::new).toList();
        Optional<ProviderChoice> selected = dialogs.showChoiceDialog(choices, defaultChoice,
                UiText.text("ui.java.ai_book_assistant_ui_service.fc35d092df"), UiText.text("ui.java.ai_book_assistant_ui_service.75ed80007a"),
                UiText.text("ui.java.ai_book_assistant_ui_service.2d385af6d6"),
                choice -> choice.info().displayName() + " [" + choice.info().id() + "]");
        if (selected.isEmpty()) return;

        dialogs.showInfo(UiText.text("ui.java.ai_book_assistant_ui_service.fdc40cc5d5"), UiText.text("ui.java.ai_book_assistant_ui_service.65e2f71a14"));
        background.submitCollectionScoped(LibraryOperationType.QUERY, () -> extract(book)).whenComplete((content, error) -> Platform.runLater(() -> {
            if (error != null) {
                dialogs.showError(UiText.text("ui.java.ai_book_assistant_ui_service.fdc40cc5d5"), UiText.text("ui.java.ai_book_assistant_ui_service.c8620fe912") + UiExceptionSupport.message(error));
                return;
            }
            IntegrationBackend.AiProviderInfo provider = selected.get().info();
            if (!confirmConsent(owner, provider, book, content)) return;
            background.submit(() -> backend.executeAi(provider.id(), book.getId(), operation, prompt, content.text(),
                            provider.networkRequired(), true))
                    .whenComplete((result, aiError) -> Platform.runLater(() -> {
                        if (aiError != null) {
                            dialogs.showError(UiText.text("ui.java.ai_book_assistant_ui_service.fdc40cc5d5"), UiText.text("ui.java.ai_book_assistant_ui_service.2eccfb44da") + UiExceptionSupport.message(aiError));
                        } else if (!result.success()) {
                            if (!result.text().isBlank()) {
                                log.warn("AI provider {} failed ({}): {}", provider.id(), result.errorKind(), result.text());
                            }
                            dialogs.showError(UiText.text("ui.java.ai_book_assistant_ui_service.fdc40cc5d5"),
                                    humanAiError(result.errorKind()) + "\n\n" + UiText.text("common.error.details_in_log"));
                        } else {
                            showAiResult(owner, book, operation, result.text(), content.truncated());
                        }
                    }));
        }));
    }

    private ExtractedBookContent extract(BookDto book) throws Exception {
        try (ResolvedBookContent resolved = resolveBookContent.execute(book, ResolveBookContentUseCase.DETAILS_EXTENSIONS)) {
            Path path = resolved.path();
            var result = extraction.extract(ContentExtractionRequest.of(new PathSource(book.getId(), path)), ContentExtractionContext.none());
            if (!result.isSuccess()) {
                throw new IOException(result.message().isBlank() ? UiText.text("ui.java.ai_book_assistant_ui_service.c786ad7a84") : result.message());
            }
            String text = result.content().text();
            if (text == null || text.isBlank()) throw new IOException(UiText.text("ui.java.ai_book_assistant_ui_service.c2a5f2b7c9"));
            if (text.length() <= AiRequest.MAX_BOOK_CONTENT_CHARS) return new ExtractedBookContent(text, false, text.length());
            int limit = AiRequest.MAX_BOOK_CONTENT_CHARS;
            int head = (int) (limit * 0.7);
            int tail = limit - head - 120;
            String sampled = text.substring(0, head)
                    + UiText.text("ui.java.ai_book_assistant_ui_service.56e3f7d722")
                    + text.substring(Math.max(head, text.length() - tail));
            if (sampled.length() > limit) sampled = sampled.substring(0, limit);
            return new ExtractedBookContent(sampled, true, text.length());
        }
    }

    private boolean confirmConsent(Window owner, IntegrationBackend.AiProviderInfo provider, BookDto book,
                                   ExtractedBookContent content) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(UiText.text("ui.java.ai_book_assistant_ui_service.0918e9da1f"));
        dialog.setHeaderText(provider.displayName());
        ButtonType send = new ButtonType(UiText.text("ui.java.ai_book_assistant_ui_service.ab958fa6d7"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(send, ButtonType.CANCEL);

        Label what = new Label(UiText.text("ui.java.ai_book_assistant_ui_service.49924484a3") + safe(book.getTitle()) + UiText.text("ui.java.ai_book_assistant_ui_service.7ae4b171dc") + content.text().length()
                + (content.truncated() ? UiText.text("ui.java.ai_book_assistant_ui_service.86a298ebd1") + content.originalLength() + UiText.text("ui.java.ai_book_assistant_ui_service.a8cfcad283") : "")
                + UiText.text("ui.java.ai_book_assistant_ui_service.99967b5384") + (provider.networkRequired() ? UiText.text("ui.java.ai_book_assistant_ui_service.d39566ef45") : UiText.text("ui.java.ai_book_assistant_ui_service.793abc1a36")));
        what.setWrapText(true);
        CheckBox contentConsent = new CheckBox(UiText.text("ui.java.ai_book_assistant_ui_service.0c9886e092"));
        contentConsent.setWrapText(true);
        CheckBox networkConsent = new CheckBox(UiText.text("ui.java.ai_book_assistant_ui_service.f64d84a500"));
        networkConsent.setWrapText(true);
        networkConsent.setVisible(provider.networkRequired());
        networkConsent.setManaged(provider.networkRequired());
        Label warning = new Label(UiText.text("ui.java.ai_book_assistant_ui_service.5060e47e76"));
        warning.setWrapText(true);
        VBox box = new VBox(12, what, contentConsent, networkConsent, warning);
        box.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(box);
        Button sendButton = (Button) dialog.getDialogPane().lookupButton(send);
        Runnable update = () -> sendButton.setDisable(!contentConsent.isSelected()
                || (provider.networkRequired() && !networkConsent.isSelected()));
        contentConsent.selectedProperty().addListener((o,a,b) -> update.run());
        networkConsent.selectedProperty().addListener((o,a,b) -> update.run());
        update.run();
        return dialog.showAndWait().filter(send::equals).isPresent();
    }

    private void showAiResult(Window owner, BookDto book, AiOperation operation, String text, boolean truncatedInput) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(operation == AiOperation.SUMMARY ? UiText.text("ui.java.ai_book_assistant_ui_service.39db7454cf") : UiText.text("ui.java.ai_book_assistant_ui_service.fb82890afa"));
        dialog.setHeaderText(safe(book.getTitle()) + (truncatedInput ? UiText.text("ui.java.ai_book_assistant_ui_service.9f4f70ff4b") : ""));
        ButtonType copy = new ButtonType(UiText.text("ui.reader.selection.copy"), ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().setAll(copy, ButtonType.CLOSE);
        TextArea area = new TextArea(text == null ? "" : text);
        area.setEditable(false); area.setWrapText(true); area.setPrefSize(780, 520);
        dialog.getDialogPane().setContent(area);
        Button copyButton = (Button) dialog.getDialogPane().lookupButton(copy);
        copyButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            ClipboardContent clipboard = new ClipboardContent(); clipboard.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(clipboard); event.consume();
        });
        dialog.showAndWait();
    }

    private void noBook() { dialogs.showWarning(UiText.text("ui.java.ai_book_assistant_ui_service.fdc40cc5d5"), UiText.text("ui.java.ai_book_assistant_ui_service.433c9e6d1d")); }

    private static String humanAiError(String kind) {
        return switch (kind == null ? "" : kind) {
            case "AUTHENTICATION" -> UiText.text("ui.java.ai_book_assistant_ui_service.de529f27ea");
            case "RATE_LIMITED" -> UiText.text("ui.java.ai_book_assistant_ui_service.ad4e96a8c8");
            case "TIMEOUT" -> UiText.text("ui.java.ai_book_assistant_ui_service.f752163adb");
            case "UNAVAILABLE" -> UiText.text("ui.java.ai_book_assistant_ui_service.4e1003f841");
            case "CONSENT_REQUIRED" -> UiText.text("ui.java.ai_book_assistant_ui_service.d40ac4dd7c");
            case "INVALID_RESPONSE" -> UiText.text("ui.java.ai_book_assistant_ui_service.9aa0503249");
            case "CANCELLED" -> UiText.text("ui.java.ai_book_assistant_ui_service.c8cfea53d6");
            default -> UiText.text("ui.java.ai_book_assistant_ui_service.e2763f49f9");
        };
    }


    private static String safe(String value) { return value == null || value.isBlank() ? UiText.text("ui.java.ai_book_assistant_ui_service.6424359615") : value; }

    private record ProviderChoice(IntegrationBackend.AiProviderInfo info) {
        @Override public String toString() { return info.displayName() + " [" + info.id() + "]"; }
    }

    private record ExtractedBookContent(String text, boolean truncated, int originalLength) { }

    private record PathSource(String id, Path path) implements ContentExtractionSource {
        @Override public String name() { return path.getFileName() == null ? path.toString() : path.getFileName().toString(); }
        @Override public InputStream openStream() throws IOException {
            return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public OptionalLong size() {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return attributes.isRegularFile() ? OptionalLong.of(attributes.size()) : OptionalLong.empty();
            } catch (IOException e) {
                return OptionalLong.empty();
            }
        }
    }
}
