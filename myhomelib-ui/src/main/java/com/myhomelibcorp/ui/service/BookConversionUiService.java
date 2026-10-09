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
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-facing conversion workflow backed by the provider-neutral conversion SPI. */
@Component
@Slf4j
public final class BookConversionUiService {
    private static final int MAX_BATCH_CONCURRENCY = 2;
    private final ConvertBookUseCase convertBook;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final OperationCenterService operations;
    private final ApplicationState applicationState;
    private final FileChooserService fileChooserService;

    public BookConversionUiService(ConvertBookUseCase convertBook,
                                   UiBackgroundExecutor background,
                                   DialogService dialogs,
                                   OperationCenterService operations,
                                   ApplicationState applicationState,
                                   FileChooserService fileChooserService) {
        this.convertBook = convertBook;
        this.background = background;
        this.dialogs = dialogs;
        this.operations = operations;
        this.applicationState = applicationState;
        this.fileChooserService = fileChooserService;
    }

    /** Converts the current book when no canonical checkbox selection was supplied. */
    public void show(Window owner, BookDto book) {
        show(owner, book, List.of());
    }

    /**
     * Converts all checkbox-selected books. If nothing is checked, falls back to the current row.
     * This deliberately reuses {@link BookSelectionService} as the only batch-selection model.
     */
    public void show(Window owner, BookDto currentBook, List<BookId> selectedIds) {
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                    UiText.text("ui.java.book_conversion_ui_service.dac9da37c6"));
            return;
        }

        List<BookId> checked = normalizeSelection(selectedIds);
        if (!checked.isEmpty()) {
            showBatch(owner, checked, expectedCollectionId);
            return;
        }
        showSingle(owner, currentBook, expectedCollectionId);
    }

    private void showSingle(Window owner, BookDto book, String expectedCollectionId) {
        if (book == null || book.getId() == null || book.getId().isBlank()) {
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                    UiText.text("ui.java.book_conversion_ui_service.433c9e6d1d"));
            return;
        }

        Map<String, List<ConvertBookUseCase.CapabilityView>> available = availableTargets(
                BookId.fromString(book.getId()), expectedCollectionId);
        if (available.isEmpty()) {
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                    UiText.text("ui.java.book_conversion_ui_service.dac9da37c6"));
            return;
        }

        ComboBox<String> format = formatSelector(available.keySet());
        Label provider = providerLabel(format, available);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.add(new Label(UiText.text("ui.annotations.book_filter")), 0, 0);
        grid.add(new Label(book.getTitle() == null ? "" : book.getTitle()), 1, 0);
        grid.add(new Label(UiText.text("ui.java.book_conversion_ui_service.743e6748a4")), 0, 1);
        grid.add(format, 1, 1);
        grid.add(provider, 1, 2);
        GridPane.setHgrow(format, Priority.ALWAYS);

        if (!confirmConversion(owner, grid)) return;
        Path outputDirectory = chooseOutputDirectory(owner);
        if (outputDirectory == null || !isCurrentCollection(expectedCollectionId)) {
            if (outputDirectory != null) {
                dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                        UiText.text("ui.java.book_conversion_ui_service.dac9da37c6"));
            }
            return;
        }
        runConversion(book, format.getValue(), outputDirectory, expectedCollectionId);
    }

    private void showBatch(Window owner, List<BookId> bookIds, String expectedCollectionId) {
        List<String> commonFormats = commonFormats(bookIds, expectedCollectionId);
        if (commonFormats.isEmpty()) {
            dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                    UiText.text("ui.conversion.batch.no_common_format"));
            return;
        }

        ComboBox<String> format = formatSelector(commonFormats);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.add(new Label(UiText.text("ui.conversion.batch.selection")), 0, 0);
        grid.add(new Label(Integer.toString(bookIds.size())), 1, 0);
        grid.add(new Label(UiText.text("ui.java.book_conversion_ui_service.743e6748a4")), 0, 1);
        grid.add(format, 1, 1);
        GridPane.setHgrow(format, Priority.ALWAYS);

        if (!confirmConversion(owner, grid)) return;
        Path outputDirectory = chooseOutputDirectory(owner);
        if (outputDirectory == null || !isCurrentCollection(expectedCollectionId)) {
            if (outputDirectory != null) {
                dialogs.showWarning(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                        UiText.text("ui.java.book_conversion_ui_service.dac9da37c6"));
            }
            return;
        }
        runConversions(bookIds, format.getValue(), outputDirectory, expectedCollectionId);
    }

    private Map<String, List<ConvertBookUseCase.CapabilityView>> availableTargets(
            BookId bookId, String expectedCollectionId) {
        Map<String, List<ConvertBookUseCase.CapabilityView>> available = new LinkedHashMap<>();
        convertBook.capabilityMatrix(bookId, expectedCollectionId).stream()
                .filter(ConvertBookUseCase.CapabilityView::available)
                .sorted(Comparator.comparing(view -> view.capability().targetFormat()))
                .forEach(view -> available.computeIfAbsent(view.capability().targetFormat(),
                        ignored -> new ArrayList<>()).add(view));
        return available;
    }

    private List<String> commonFormats(List<BookId> bookIds, String expectedCollectionId) {
        LinkedHashSet<String> common = null;
        for (BookId bookId : bookIds) {
            Set<String> targets = availableTargets(bookId, expectedCollectionId).keySet();
            if (common == null) common = new LinkedHashSet<>(targets);
            else common.retainAll(targets);
            if (common.isEmpty()) return List.of();
        }
        if (common == null) return List.of();
        return common.stream().sorted().toList();
    }

    private static List<BookId> normalizeSelection(List<BookId> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) return List.of();
        LinkedHashSet<BookId> unique = new LinkedHashSet<>();
        for (BookId id : selectedIds) if (id != null) unique.add(id);
        return List.copyOf(unique);
    }

    private static ComboBox<String> formatSelector(java.util.Collection<String> formats) {
        ComboBox<String> format = new ComboBox<>();
        format.getItems().setAll(formats);
        format.getSelectionModel().selectFirst();
        return format;
    }

    private static Label providerLabel(ComboBox<String> format,
                                       Map<String, List<ConvertBookUseCase.CapabilityView>> available) {
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
        return provider;
    }

    private static boolean confirmConversion(Window owner, GridPane content) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(UiText.text("ui.java.book_conversion_ui_service.79797becbd"));
        dialog.setHeaderText(UiText.text("ui.java.book_conversion_ui_service.cdb7bf971e"));
        ButtonType convert = new ButtonType(UiText.text("ui.java.book_conversion_ui_service.7469f9d505"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(UiText.text("ui.fxml.import_workspace.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(convert, cancel);
        dialog.getDialogPane().setContent(content);
        return dialog.showAndWait().filter(convert::equals).isPresent();
    }

    private Path chooseOutputDirectory(Window owner) {
        File selected = fileChooserService.chooseDirectory(owner, UiText.text("ui.java.book_conversion_ui_service.f9317efd35"));
        return selected == null ? null : selected.toPath();
    }

    private void runConversion(BookDto book, String format, Path outputDirectory,
                               String expectedCollectionId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        String operationId = operations.start(UiText.text("ui.java.book_conversion_ui_service.21a9f9188e") + safeTitle(book),
                expectedCollectionId, OperationKind.BOOK_CONVERSION, OperationStage.CONVERTING,
                true, () -> cancelled.set(true));

        background.submitCollectionScopedOperation(LibraryOperationType.CONVERSION,
                () -> executeConversion(BookId.fromString(book.getId()), format, outputDirectory,
                        expectedCollectionId, cancelled))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    Throwable failure = error == null ? null : unwrap(error);
                    if (!isCurrentCollection(expectedCollectionId)) {
                        finishDetached(operationId, result, failure);
                        return;
                    }
                    if (failure instanceof java.util.concurrent.CancellationException) {
                        operations.cancel(operationId, UiText.text("ui.import.stage.cancelled"));
                        return;
                    }
                    if (failure != null) {
                        log.error("Book conversion failed: bookId={}, targetFormat={}, collectionId={}, outputDirectory={}, cause={}",
                                book.getId(), format, expectedCollectionId, outputDirectory,
                                UiExceptionSupport.technicalMessage(failure), failure);
                        operations.fail(operationId, failure);
                        dialogs.showError(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                                UiText.text("ui.java.book_conversion_ui_service.78400b9a85") + message(failure));
                        return;
                    }
                    operations.complete(operationId,
                            UiText.text("ui.java.book_conversion_ui_service.d11255b49b") + result.artifact().displayName());
                    dialogs.showInfo(UiText.text("ui.java.book_conversion_ui_service.79797becbd"),
                            UiText.text("ui.java.book_conversion_ui_service.bda46803b0") + format.toUpperCase(java.util.Locale.ROOT)
                                    + UiText.text("ui.java.book_conversion_ui_service.bf9f97a3ce") + result.artifact().displayName()
                                    + UiText.text("ui.java.book_conversion_ui_service.57cb7ad0be") + converterName(result.converterId()));
                }));
    }

    /** Shared Operation Center row; individual conversions still run under normal collection-scoped leases. */
    private void runConversions(List<BookId> bookIds, String format, Path outputDirectory,
                                String expectedCollectionId) {
        List<BookId> normalized = normalizeSelection(bookIds);
        if (normalized.isEmpty()) return;

        AtomicBoolean cancelled = new AtomicBoolean(false);
        String operationId = operations.start(UiText.text("ui.conversion.batch.operation").formatted(normalized.size()),
                expectedCollectionId, OperationKind.BOOK_CONVERSION, OperationStage.CONVERTING,
                true, () -> cancelled.set(true));

        List<CompletableFuture<BatchOutcome>> futures = new ArrayList<>(normalized.size());
        for (int i = 0; i < normalized.size(); i++) futures.add(new CompletableFuture<>());

        int lanes = Math.min(MAX_BATCH_CONCURRENCY, normalized.size());
        List<CompletableFuture<Void>> laneTails = new ArrayList<>(lanes);
        for (int i = 0; i < lanes; i++) laneTails.add(CompletableFuture.completedFuture(null));

        // Keep only a small number of external converters in flight. Enqueuing the entire batch
        // into the shared UI executor would let a large selection starve navigation/Reader work.
        for (int i = 0; i < normalized.size(); i++) {
            BookId bookId = normalized.get(i);
            CompletableFuture<BatchOutcome> outcome = futures.get(i);
            int lane = i % lanes;
            CompletableFuture<Void> next = laneTails.get(lane).thenCompose(ignored -> {
                if (cancelled.get()) {
                    outcome.complete(new BatchOutcome(bookId, null,
                            new java.util.concurrent.CancellationException("Batch book conversion cancelled")));
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    return background.submitCollectionScopedOperation(LibraryOperationType.CONVERSION,
                                    () -> executeConversion(bookId, format, outputDirectory,
                                            expectedCollectionId, cancelled))
                            .handle((result, error) -> {
                                outcome.complete(new BatchOutcome(bookId, result,
                                        error == null ? null : unwrap(error)));
                                return null;
                            });
                } catch (Throwable schedulingFailure) {
                    outcome.complete(new BatchOutcome(bookId, null, unwrap(schedulingFailure)));
                    return CompletableFuture.completedFuture(null);
                }
            });
            laneTails.set(lane, next);
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, aggregateError) -> Platform.runLater(() -> {
                    List<BatchOutcome> outcomes = futures.stream().map(CompletableFuture::join).toList();
                    long failures = outcomes.stream().filter(outcome -> outcome.failure() != null
                            && !(outcome.failure() instanceof java.util.concurrent.CancellationException)).count();
                    long cancelledCount = outcomes.stream().filter(outcome ->
                            outcome.failure() instanceof java.util.concurrent.CancellationException).count();
                    long successes = outcomes.size() - failures - cancelledCount;

                    if (cancelled.get() || cancelledCount > 0) {
                        operations.cancel(operationId, UiText.text("ui.import.stage.cancelled"));
                        return;
                    }
                    if (failures > 0) {
                        outcomes.stream().filter(outcome -> outcome.failure() != null).forEach(outcome ->
                                log.error("Batch book conversion failed: bookId={}, targetFormat={}, collectionId={}, outputDirectory={}, cause={}",
                                        outcome.bookId(), format, expectedCollectionId, outputDirectory,
                                        UiExceptionSupport.technicalMessage(outcome.failure()), outcome.failure()));
                        String summary = UiText.text("ui.conversion.batch.partial").formatted(successes, failures);
                        operations.fail(operationId, summary);
                        if (isCurrentCollection(expectedCollectionId)) {
                            dialogs.showError(UiText.text("ui.java.book_conversion_ui_service.79797becbd"), summary);
                        }
                        return;
                    }

                    String summary = UiText.text("ui.conversion.batch.completed").formatted(successes);
                    operations.complete(operationId, summary);
                    if (isCurrentCollection(expectedCollectionId)) {
                        dialogs.showInfo(UiText.text("ui.java.book_conversion_ui_service.79797becbd"), summary);
                    }
                }));
    }

    private ConvertBookUseCase.Result executeConversion(BookId bookId, String format, Path outputDirectory,
                                                        String expectedCollectionId, AtomicBoolean cancelled) throws Exception {
        return convertBook.execute(new ConvertBookUseCase.Request(
                        bookId, format, outputDirectory,
                        ConvertBookUseCase.DEFAULT_MAX_INPUT_BYTES,
                        ConvertBookUseCase.DEFAULT_MAX_OUTPUT_BYTES,
                        cancelled::get), expectedCollectionId);
    }

    private void finishDetached(String operationId, ConvertBookUseCase.Result result, Throwable failure) {
        if (failure instanceof java.util.concurrent.CancellationException) {
            operations.cancel(operationId, UiText.text("ui.import.stage.cancelled"));
        } else if (failure != null) {
            operations.fail(operationId, failure);
        } else {
            operations.complete(operationId,
                    UiText.text("ui.java.book_conversion_ui_service.d11255b49b") + result.artifact().displayName());
        }
    }

    private record BatchOutcome(BookId bookId, ConvertBookUseCase.Result result, Throwable failure) { }

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
