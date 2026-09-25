package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.application.usecase.conversion.BookConversionException;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-facing conversion workflow backed by the provider-neutral conversion SPI. */
@Component
@Slf4j
public final class BookConversionUiService {
    private final ConvertBookUseCase convertBook;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final OperationCenterService operations;
    private final ApplicationState applicationState;

    public BookConversionUiService(ConvertBookUseCase convertBook,
                                   UiBackgroundExecutor background,
                                   DialogService dialogs,
                                   OperationCenterService operations,
                                   ApplicationState applicationState) {
        this.convertBook = convertBook;
        this.background = background;
        this.dialogs = dialogs;
        this.operations = operations;
        this.applicationState = applicationState;
    }

    public void show(Window owner, BookDto book) {
        if (book == null || book.getId() == null || book.getId().isBlank()) {
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"), UiText.text("ui.java.book_conversion_ui_service.433c9e6d1d"));
            return;
        }

        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                    UiText.text("ui.java.book_conversion_ui_service.dac9da37c6"));
            return;
        }

        Map<String, List<ConvertBookUseCase.CapabilityView>> available = new LinkedHashMap<>();
        BookId bookId = BookId.fromString(book.getId());
        convertBook.capabilityMatrix(bookId, expectedCollectionId).stream()
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
            List<String> providers = list.stream().map(view -> converterName(view.converterId())).distinct().toList();
            if (providers.isEmpty()) {
                provider.setText("");
            } else if (providers.size() == 1) {
                provider.setText(UiText.text("ui.conversion.provider.single").formatted(providers.getFirst()));
            } else {
                provider.setText(UiText.text("ui.conversion.provider.priority").formatted(
                        providers.getFirst(), String.join(", ", providers.subList(1, providers.size()))));
            }
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
        if (!isCurrentCollection(expectedCollectionId)) {
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                    UiText.text("ui.java.book_conversion_ui_service.dac9da37c6"));
            return;
        }
        runConversion(book, format.getValue(), selected.toPath(), preferred.isSelected(), expectedCollectionId);
    }

    private void runConversion(BookDto book, String format, Path outputDirectory, boolean makePreferred,
                               String expectedCollectionId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        String operationId = operations.start(UiText.text("ui.java.book_conversion_ui_service.21a9f9188e") + safeTitle(book), expectedCollectionId, OperationKind.BOOK_CONVERSION,
                OperationStage.CONVERTING, false);

        background.submitCollectionScoped(LibraryOperationType.CONVERSION,
                () -> convertBook.execute(new ConvertBookUseCase.Request(
                BookId.fromString(book.getId()), format, outputDirectory, makePreferred,
                ConvertBookUseCase.DEFAULT_MAX_INPUT_BYTES, ConvertBookUseCase.DEFAULT_MAX_OUTPUT_BYTES, cancelled::get),
                        expectedCollectionId))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (!isCurrentCollection(expectedCollectionId)) {
                        if (error != null) operations.fail(operationId, unwrap(error));
                        else operations.complete(operationId, UiText.text("ui.java.book_conversion_ui_service.d11255b49b") + result.artifact().displayName());
                        return;
                    }
                    if (error != null) {
                        Throwable failure = unwrap(error);
                        log.error("Book conversion failed: bookId={}, targetFormat={}, collectionId={}, outputDirectory={}, cause={}",
                                book.getId(), format, expectedCollectionId, outputDirectory,
                                UiExceptionSupport.technicalMessage(failure), failure);
                        operations.fail(operationId, failure);
                        dialogs.showError(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                                UiText.text("ui.java.book_conversion_ui_service.78400b9a85") + message(failure));
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

    private boolean isCurrentCollection(String expectedCollectionId) {
        return Objects.equals(expectedCollectionId, applicationState.getCurrentLibraryCollectionId());
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String message(Throwable error) {
        Throwable failure = unwrap(error);
        if (failure instanceof BookConversionException conversion) {
            String provider = converterName(conversion.providerId());
            String target = conversion.targetFormat().isBlank()
                    ? "" : conversion.targetFormat().toUpperCase(java.util.Locale.ROOT);
            String details = switch (conversion.stage()) {
                case SOURCE_READ -> UiText.text("ui.conversion.error.source_read");
                case PROVIDER -> UiText.text("ui.conversion.error.provider").formatted(provider, target);
                case OUTPUT_VALIDATION -> UiText.text("ui.conversion.error.output_validation").formatted(provider, target);
                case PUBLICATION -> UiText.text("ui.conversion.error.publication").formatted(target);
                case CATALOG_REGISTRATION -> UiText.text("ui.conversion.error.catalog_registration").formatted(target);
            };
            return details + " " + UiText.text("common.error.details_in_log");
        }
        return UiExceptionSupport.message(failure);
    }

    private static String safeTitle(BookDto book) {
        return book.getTitle() == null || book.getTitle().isBlank() ? book.getId() : book.getTitle();
    }
}
