package com.myhomelibcorp.ui.integration;

import com.myhomelibcorp.shared.util.ThrowableText;
import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.content.ContentExtractionSource;
import com.myhomelibcorp.application.dto.BookDto;
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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Explicit, consent-gated AI actions for the currently selected book. */
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
                "Склади структурований стислий підсумок книги: основні теми, ключові події або аргументи та важливі висновки. Не вигадуй того, чого немає в наданому тексті.");
    }

    public void ask(Window owner, BookDto book) {
        if (book == null) { noBook(); return; }
        Optional<String> prompt = dialogs.showTextInput("Запитання до книги", "ШІ відповідатиме на основі тексту вибраної книги.",
                "Ваше запитання", "");
        if (prompt.isEmpty() || prompt.get().isBlank()) return;
        run(owner, book, AiOperation.QUESTION_ANSWER, prompt.get());
    }

    private void run(Window owner, BookDto book, AiOperation operation, String prompt) {
        if (book == null) { noBook(); return; }
        List<IntegrationBackend.AiProviderInfo> available = backend.aiProviders().stream()
                .filter(provider -> provider.operations().contains(operation.name()))
                .toList();
        if (available.isEmpty()) {
            dialogs.showWarning("ШІ", "Немає провайдера, який підтримує цю дію. Відкрийте «Синхронізація, плагіни та ШІ…» і налаштуйте провайдера.");
            return;
        }
        ProviderChoice defaultChoice = new ProviderChoice(available.getFirst());
        List<ProviderChoice> choices = available.stream().map(ProviderChoice::new).toList();
        Optional<ProviderChoice> selected = dialogs.showChoiceDialog(choices, defaultChoice,
                "ШІ для книги", "Виберіть провайдера", "Провайдер");
        if (selected.isEmpty()) return;

        dialogs.showInfo("ШІ", "Готується текст вибраної книги. Після цього буде показано точний запит на згоду перед передачею даних.");
        background.submit(() -> extract(book)).whenComplete((content, error) -> Platform.runLater(() -> {
            if (error != null) {
                dialogs.showError("ШІ", "Не вдалося підготувати текст книги: " + ThrowableText.rootMessage(error, "невідома помилка"));
                return;
            }
            IntegrationBackend.AiProviderInfo provider = selected.get().info();
            if (!confirmConsent(owner, provider, book, content)) return;
            background.submit(() -> backend.executeAi(provider.id(), book.getId(), operation, prompt, content.text(),
                            provider.networkRequired(), true))
                    .whenComplete((result, aiError) -> Platform.runLater(() -> {
                        if (aiError != null) {
                            dialogs.showError("ШІ", "Не вдалося виконати запит: " + ThrowableText.rootMessage(aiError, "невідома помилка"));
                        } else if (!result.success()) {
                            dialogs.showError("ШІ", humanAiError(result.errorKind()) + "\n\n" + result.text());
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
                throw new IOException(result.message().isBlank() ? "Формат книги не підтримує витяг тексту" : result.message());
            }
            String text = result.content().text();
            if (text == null || text.isBlank()) throw new IOException("У книзі не знайдено тексту для аналізу");
            if (text.length() <= AiRequest.MAX_BOOK_CONTENT_CHARS) return new ExtractedBookContent(text, false, text.length());
            int limit = AiRequest.MAX_BOOK_CONTENT_CHARS;
            int head = (int) (limit * 0.7);
            int tail = limit - head - 120;
            String sampled = text.substring(0, head)
                    + "\n\n[... середню частину скорочено через ліміт передачі ...]\n\n"
                    + text.substring(Math.max(head, text.length() - tail));
            if (sampled.length() > limit) sampled = sampled.substring(0, limit);
            return new ExtractedBookContent(sampled, true, text.length());
        }
    }

    private boolean confirmConsent(Window owner, IntegrationBackend.AiProviderInfo provider, BookDto book,
                                   ExtractedBookContent content) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Підтвердження передачі даних ШІ");
        dialog.setHeaderText(provider.displayName());
        ButtonType send = new ButtonType("Надіслати", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(send, ButtonType.CANCEL);

        Label what = new Label("Книга: " + safe(book.getTitle()) + "\nБуде передано символів тексту: " + content.text().length()
                + (content.truncated() ? " із " + content.originalLength() + " (великий текст скорочено)" : "")
                + "\nМережевий доступ провайдера: " + (provider.networkRequired() ? "так" : "ні"));
        what.setWrapText(true);
        CheckBox contentConsent = new CheckBox("Я дозволяю передати вказаний текст вибраної книги цьому провайдеру ШІ.");
        contentConsent.setWrapText(true);
        CheckBox networkConsent = new CheckBox("Я дозволяю виконати мережевий запит до цього провайдера.");
        networkConsent.setWrapText(true);
        networkConsent.setVisible(provider.networkRequired());
        networkConsent.setManaged(provider.networkRequired());
        Label warning = new Label("MyHomeLib не запускає цю дію автоматично. Для мережевого провайдера текст залишає ваш комп’ютер відповідно до умов обраного сервісу.");
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
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(operation == AiOperation.SUMMARY ? "Підсумок книги" : "Відповідь ШІ");
        dialog.setHeaderText(safe(book.getTitle()) + (truncatedInput ? " · використано скорочену вибірку тексту" : ""));
        ButtonType copy = new ButtonType("Копіювати", ButtonBar.ButtonData.OTHER);
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

    private void noBook() { dialogs.showWarning("ШІ", "Спочатку виберіть книгу."); }

    private static String humanAiError(String kind) {
        return switch (kind == null ? "" : kind) {
            case "AUTHENTICATION" -> "Помилка автентифікації провайдера.";
            case "RATE_LIMITED" -> "Провайдер тимчасово обмежив кількість запитів.";
            case "TIMEOUT" -> "Перевищено час очікування відповіді.";
            case "UNAVAILABLE" -> "Провайдер тимчасово недоступний.";
            case "CONSENT_REQUIRED" -> "Не надано необхідної згоди.";
            case "INVALID_RESPONSE" -> "Провайдер повернув некоректну відповідь.";
            case "CANCELLED" -> "Запит скасовано.";
            default -> "Запит ШІ завершився помилкою.";
        };
    }


    private static String safe(String value) { return value == null || value.isBlank() ? "Без назви" : value; }

    private record ProviderChoice(IntegrationBackend.AiProviderInfo info) {
        @Override public String toString() { return info.displayName() + " [" + info.id() + "]"; }
    }

    private record ExtractedBookContent(String text, boolean truncated, int originalLength) { }

    private record PathSource(String id, Path path) implements ContentExtractionSource {
        @Override public String name() { return path.getFileName() == null ? path.toString() : path.getFileName().toString(); }
        @Override public InputStream openStream() throws IOException { return Files.newInputStream(path); }
        @Override public OptionalLong size() {
            try { return OptionalLong.of(Files.size(path)); } catch (IOException e) { return OptionalLong.empty(); }
        }
    }
}
