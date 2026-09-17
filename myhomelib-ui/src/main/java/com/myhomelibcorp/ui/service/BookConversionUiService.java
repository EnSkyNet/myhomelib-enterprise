package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-facing conversion workflow backed by the provider-neutral conversion SPI. */
@Component
public final class BookConversionUiService {
    private final ConvertBookUseCase convertBook;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final OperationCenterService operations;

    public BookConversionUiService(ConvertBookUseCase convertBook,
                                   UiBackgroundExecutor background,
                                   DialogService dialogs,
                                   OperationCenterService operations) {
        this.convertBook = convertBook;
        this.background = background;
        this.dialogs = dialogs;
        this.operations = operations;
    }

    public void show(Window owner, BookDto book) {
        if (book == null || book.getId() == null || book.getId().isBlank()) {
            dialogs.showWarning("Конвертація книги", "Спочатку виберіть книгу.");
            return;
        }

        Map<String, List<ConvertBookUseCase.CapabilityView>> available = new LinkedHashMap<>();
        BookId bookId = BookId.fromString(book.getId());
        convertBook.capabilityMatrix(bookId).stream()
                .filter(ConvertBookUseCase.CapabilityView::available)
                .sorted(Comparator.comparing(view -> view.capability().targetFormat()))
                .forEach(view -> available.computeIfAbsent(view.capability().targetFormat(), ignored -> new java.util.ArrayList<>()).add(view));
        if (available.isEmpty()) {
            dialogs.showWarning("Конвертація книги",
                    "Доступних конвертерів не знайдено. Встановіть calibre або увімкніть плагін-конвертер у «Синхронізація, плагіни та ШІ».");
            return;
        }

        ComboBox<String> format = new ComboBox<>();
        format.getItems().setAll(available.keySet());
        format.getSelectionModel().selectFirst();
        CheckBox preferred = new CheckBox("Зробити створений формат основним для цієї книги");
        Label provider = new Label();
        provider.setWrapText(true);
        Runnable updateProvider = () -> {
            List<ConvertBookUseCase.CapabilityView> list = available.getOrDefault(format.getValue(), List.of());
            provider.setText(list.isEmpty() ? "" : "Доступний рушій: " + list.stream().map(view -> converterName(view.converterId())).distinct().reduce((a,b) -> a + ", " + b).orElse(""));
        };
        format.valueProperty().addListener((obs, old, value) -> updateProvider.run());
        updateProvider.run();

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.add(new Label("Книга"), 0, 0); grid.add(new Label(book.getTitle() == null ? "" : book.getTitle()), 1, 0);
        grid.add(new Label("Формат результату"), 0, 1); grid.add(format, 1, 1);
        grid.add(provider, 1, 2);
        grid.add(preferred, 1, 3);
        GridPane.setHgrow(format, Priority.ALWAYS);

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Конвертація книги");
        dialog.setHeaderText("Створити додатковий формат книги");
        ButtonType convert = new ButtonType("Конвертувати", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Скасувати", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(convert, cancel);
        dialog.getDialogPane().setContent(grid);
        if (dialog.showAndWait().filter(convert::equals).isEmpty()) return;

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Папка для сконвертованої книги");
        File selected = chooser.showDialog(owner);
        if (selected == null) return;
        runConversion(book, format.getValue(), selected.toPath(), preferred.isSelected());
    }

    private void runConversion(BookDto book, String format, Path outputDirectory, boolean makePreferred) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        String operationId = operations.start("Конвертація: " + safeTitle(book), "", OperationKind.BOOK_CONVERSION,
                OperationStage.CONVERTING, false);

        background.submit(() -> convertBook.execute(new ConvertBookUseCase.Request(
                BookId.fromString(book.getId()), format, outputDirectory, makePreferred,
                ConvertBookUseCase.DEFAULT_MAX_INPUT_BYTES, ConvertBookUseCase.DEFAULT_MAX_OUTPUT_BYTES, cancelled::get)))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        operations.fail(operationId, unwrap(error));
                        dialogs.showError("Конвертація книги", "Не вдалося виконати конвертацію: " + message(unwrap(error)));
                        return;
                    }
                    operations.complete(operationId, "Створено " + result.artifact().displayName());
                    dialogs.showInfo("Конвертація книги",
                            "Готово. Створено формат " + format.toUpperCase(java.util.Locale.ROOT)
                                    + "\nФайл: " + result.artifact().displayName()
                                    + "\nРушій: " + result.converterId());
                }));
    }

    private static String converterName(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("plugin:calibre") || normalized.startsWith("calibre-cli:")) return "calibre";
        if (normalized.startsWith("plugin-export:")) return "плагін експорту";
        return id == null || id.isBlank() ? "конвертер" : id;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String message(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.isBlank() ? (error == null ? "Невідома помилка" : error.getClass().getSimpleName()) : value;
    }

    private static String safeTitle(BookDto book) {
        return book.getTitle() == null || book.getTitle().isBlank() ? book.getId() : book.getTitle();
    }
}
