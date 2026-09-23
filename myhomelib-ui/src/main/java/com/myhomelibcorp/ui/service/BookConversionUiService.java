package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
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
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"), UiText.text("ui.java.book_conversion_ui_service.433c9e6d1d"));
            return;
        }

        Map<String, List<ConvertBookUseCase.CapabilityView>> available = new LinkedHashMap<>();
        BookId bookId = BookId.fromString(book.getId());
        convertBook.capabilityMatrix(bookId).stream()
                .filter(ConvertBookUseCase.CapabilityView::available)
                .sorted(Comparator.comparing(view -> view.capability().targetFormat()))
                .forEach(view -> available.computeIfAbsent(view.capability().targetFormat(), ignored -> new java.util.ArrayList<>()).add(view));
        if (available.isEmpty()) {
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                    UiText.text("ui.java.book_conversion_ui_service.dac9da37c6"));
            return;
        }

        ComboBox<String> format = new ComboBox<>();
        format.getItems().setAll(available.keySet());
        format.getSelectionModel().selectFirst();
        CheckBox preferred = new CheckBox(UiText.text("ui.java.book_conversion_ui_service.5ee327cabf"));
        Label provider = new Label();
        provider.setWrapText(true);
        Runnable updateProvider = () -> {
            List<ConvertBookUseCase.CapabilityView> list = available.getOrDefault(format.getValue(), List.of());
            provider.setText(list.isEmpty() ? "" : UiText.text("ui.java.book_conversion_ui_service.a746bb622e") + list.stream().map(view -> converterName(view.converterId())).distinct().reduce((a,b) -> a + ", " + b).orElse(""));
        };
        format.valueProperty().addListener((obs, old, value) -> updateProvider.run());
        updateProvider.run();

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.add(new Label(UiText.text("ui.annotations.book_filter")), 0, 0); grid.add(new Label(book.getTitle() == null ? "" : book.getTitle()), 1, 0);
        grid.add(new Label(UiText.text("ui.java.book_conversion_ui_service.743e6748a4")), 0, 1); grid.add(format, 1, 1);
        grid.add(provider, 1, 2);
        grid.add(preferred, 1, 3);
        GridPane.setHgrow(format, Priority.ALWAYS);

        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(UiText.text("ui.java.book_conversion_ui_service.79797becbd"));
        dialog.setHeaderText(UiText.text("ui.java.book_conversion_ui_service.cdb7bf971e"));
        ButtonType convert = new ButtonType(UiText.text("ui.java.book_conversion_ui_service.7469f9d505"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(UiText.text("ui.fxml.import_workspace.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(convert, cancel);
        dialog.getDialogPane().setContent(grid);
        if (dialog.showAndWait().filter(convert::equals).isEmpty()) return;

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(UiText.text("ui.java.book_conversion_ui_service.f9317efd35"));
        File selected = chooser.showDialog(owner);
        if (selected == null) return;
        runConversion(book, format.getValue(), selected.toPath(), preferred.isSelected());
    }

    private void runConversion(BookDto book, String format, Path outputDirectory, boolean makePreferred) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        String operationId = operations.start(UiText.text("ui.java.book_conversion_ui_service.21a9f9188e") + safeTitle(book), "", OperationKind.BOOK_CONVERSION,
                OperationStage.CONVERTING, false);

        background.submitCollectionScoped(LibraryOperationType.CONVERSION,
                () -> convertBook.execute(new ConvertBookUseCase.Request(
                BookId.fromString(book.getId()), format, outputDirectory, makePreferred,
                ConvertBookUseCase.DEFAULT_MAX_INPUT_BYTES, ConvertBookUseCase.DEFAULT_MAX_OUTPUT_BYTES, cancelled::get)))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        operations.fail(operationId, unwrap(error));
                        dialogs.showError(UiText.text("ui.java.book_conversion_ui_service.79797becbd"), UiText.text("ui.java.book_conversion_ui_service.78400b9a85") + message(unwrap(error)));
                        return;
                    }
                    operations.complete(operationId, UiText.text("ui.java.book_conversion_ui_service.d11255b49b") + result.artifact().displayName());
                    dialogs.showInfo(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                            UiText.text("ui.java.book_conversion_ui_service.bda46803b0") + format.toUpperCase(java.util.Locale.ROOT)
                                    + UiText.text("ui.java.book_conversion_ui_service.bf9f97a3ce") + result.artifact().displayName()
                                    + UiText.text("ui.java.book_conversion_ui_service.57cb7ad0be") + converterName(result.converterId()));
                }));
    }

    private static String converterName(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("myhomelib-toolchain")) return UiText.text("ui.conversion.provider.toolchain");
        if (normalized.startsWith("plugin:calibre") || normalized.startsWith("calibre-cli:") || normalized.contains(":calibre")) {
            return UiText.text("ui.conversion.provider.calibre");
        }
        if (normalized.contains("externalbookconverter") || normalized.contains("externalfromfb2converter")) {
            return UiText.text("ui.conversion.provider.external");
        }
        if (normalized.endsWith("txtbookconverter") || normalized.endsWith("fb2bookconverter")
                || normalized.endsWith("epubbookconverter") || normalized.endsWith("fb2zipbookconverter")) {
            return UiText.text("ui.conversion.provider.native");
        }
        if (normalized.startsWith("plugin-export:")) return UiText.text("ui.java.book_conversion_ui_service.076d9b65e0");
        if (normalized.startsWith("plugin:")) return UiText.text("ui.conversion.provider.plugin");
        return UiText.text("ui.conversion.provider.unknown");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String message(Throwable error) {
        return UiExceptionSupport.message(error);
    }

    private static String safeTitle(BookDto book) {
        return book.getTitle() == null || book.getTitle().isBlank() ? book.getId() : book.getTitle();
    }
}
