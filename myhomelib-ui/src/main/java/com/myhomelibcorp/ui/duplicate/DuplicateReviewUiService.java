package com.myhomelibcorp.ui.duplicate;

import com.myhomelibcorp.ui.service.DialogLocalization;

import com.myhomelibcorp.ui.service.OperationStageText;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.duplicate.ExactDuplicateGroup;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanResult;
import com.myhomelibcorp.application.duplicate.merge.BookMergePlan;
import com.myhomelibcorp.application.duplicate.merge.BookMergeResult;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.duplicate.ExactDuplicateScanner;
import com.myhomelibcorp.application.usecase.duplicate.MergeBooksUseCase;
import com.myhomelibcorp.application.usecase.duplicate.ReviewBookDuplicatesUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Production UI for exact/fuzzy duplicate review plus explicit undoable logical-book merge. */
@Component
@RequiredArgsConstructor
public class DuplicateReviewUiService {
    private final ApplicationState appState;
    private final ReviewBookDuplicatesUseCase fuzzyReview;
    private final ExactDuplicateScanner exactScanner;
    private final MergeBooksUseCase mergeBooks;
    private final LoadBookByIdUseCase loadBook;
    private final DuplicateReviewPresenter presenter;
    private final UiBackgroundExecutor background;
    private final OperationCenterService operationCenter;
    private final DialogService dialogs;

    public void show(Window owner) {
        if (appState.getCurrentLibraryCollection() == null) {
            dialogs.showWarning(UiText.text("ui.fxml.integrity_check.duplicates"), UiText.text("ui.java.duplicate_review_ui_service.6686ed3c16"));
            return;
        }
        Stage stage = new Stage();
        stage.setTitle(UiText.text("ui.java.duplicate_review_ui_service.6945022608"));
        stage.initModality(Modality.NONE);
        if (owner != null) stage.initOwner(owner);

        TabPane tabs = new TabPane();
        tabs.getTabs().add(fuzzyTab());
        tabs.getTabs().add(exactTab());
        BorderPane root = new BorderPane(tabs);
        root.setPadding(new Insets(10));
        stage.setScene(new Scene(root, 1040, 650));
        stage.show();
    }

    private Tab fuzzyTab() {
        Label sourceTitle = new Label();
        sourceTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label sourceMeta = new Label();
        sourceMeta.setWrapText(true);
        Label sourceArtifacts = new Label();
        sourceArtifacts.setWrapText(true);
        Label status = new Label(UiText.text("ui.java.duplicate_review_ui_service.d50b4eef7e"));
        status.setWrapText(true);

        TableView<DuplicateReviewRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(column(UiText.text("ui.java.duplicate_review_ui_service.0e4e1b8481"), DuplicateReviewRow::candidateTitle, 230));
        table.getColumns().add(column(UiText.text("ui.annotations.digest_author"), DuplicateReviewRow::candidateAuthors, 180));
        table.getColumns().add(column(UiText.text("ui.bulkedit.field.year"), DuplicateReviewRow::candidateYear, 70));
        table.getColumns().add(column("ISBN", DuplicateReviewRow::candidateIsbn, 130));
        table.getColumns().add(column(UiText.text("ui.java.duplicate_review_ui_service.bdc610b581"), DuplicateReviewRow::score, 85));
        table.getColumns().add(column(UiText.text("ui.java.duplicate_review_ui_service.4a02475f1f"), DuplicateReviewRow::reasons, 260));
        table.getColumns().add(column(UiText.text("ui.java.duplicate_review_ui_service.125e608d8f"), DuplicateReviewRow::artifacts, 300));
        table.setPlaceholder(new Label(UiText.text("ui.java.duplicate_review_ui_service.a6f9844596")));

        ProgressIndicator loading = new ProgressIndicator();
        loading.setPrefSize(24, 24);
        loading.setVisible(false);
        Button refresh = new Button(UiText.text("ui.java.duplicate_review_ui_service.d569fabe33"));
        Button merge = new Button(UiText.text("ui.java.duplicate_review_ui_service.83d808d6ef"));
        Button undo = new Button(UiText.text("ui.java.duplicate_review_ui_service.1c48a78d03"));
        merge.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> merge.setDisable(row == null));
        HBox actions = new HBox(10, refresh, merge, undo, loading);
        AtomicReference<FuzzyDisplayContext> displayed = new AtomicReference<>();
        AtomicLong loadGeneration = new AtomicLong();

        Runnable load = () -> loadFuzzy(sourceTitle, sourceMeta, sourceArtifacts, status, table, refresh, loading,
                displayed, loadGeneration);
        refresh.setOnAction(event -> load.run());
        merge.setOnAction(event -> reviewAndMerge(table.getSelectionModel().getSelectedItem(), status, load, displayed));
        undo.setOnAction(event -> undoLatest(status, load, displayed));
        Platform.runLater(load);

        VBox content = new VBox(8,
                new Label(UiText.text("ui.java.duplicate_review_ui_service.0e7c32d612")), sourceTitle, sourceMeta,
                new Label(UiText.text("ui.java.duplicate_review_ui_service.8347524e08")), sourceArtifacts,
                new Separator(), actions, status, table);
        content.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        Tab tab = new Tab(UiText.text("ui.java.duplicate_review_ui_service.358bf8ee1f"), content);
        tab.setClosable(false);
        return tab;
    }

    private void loadFuzzy(Label sourceTitle, Label sourceMeta, Label sourceArtifacts, Label status,
                           TableView<DuplicateReviewRow> table, Button refresh, ProgressIndicator loading,
                           AtomicReference<FuzzyDisplayContext> displayed, AtomicLong loadGeneration) {
        long generation = loadGeneration.incrementAndGet();
        displayed.set(null);
        String expectedCollectionId = appState.getCurrentLibraryCollectionId();
        BookDto selected = appState.getBookDetails().getCurrentBook();
        if (selected == null || selected.getId() == null || selected.getId().isBlank()) {
            sourceTitle.setText(UiText.text("ui.java.duplicate_review_ui_service.1215889a21"));
            sourceMeta.setText(UiText.text("ui.java.duplicate_review_ui_service.f56e10ef19"));
            sourceArtifacts.setText("—");
            table.setItems(FXCollections.observableArrayList());
            return;
        }
        String expectedSourceBookId = selected.getId();
        sourceTitle.setText(selected.getTitle());
        sourceMeta.setText(selected.getAuthorsText() == null ? "" : selected.getAuthorsText());
        sourceArtifacts.setText(presenter.artifacts(selected));
        status.setText(UiText.text("ui.java.duplicate_review_ui_service.4ddbf5342d"));
        refresh.setDisable(true);
        loading.setVisible(true);

        background.submitCollectionScoped(LibraryOperationType.QUERY,
                () -> fuzzyReview.review(BookId.fromString(selected.getId()), ReviewBookDuplicatesUseCase.DEFAULT_LIMIT))
                .whenComplete((suggestions, error) -> UiExecutor.runOnUiThread(() -> {
                    if (generation != loadGeneration.get()) return;
                    refresh.setDisable(false);
                    loading.setVisible(false);
                    if (!isCurrentBook(expectedCollectionId, expectedSourceBookId)) {
                        table.setItems(FXCollections.observableArrayList());
                        displayed.set(null);
                        return;
                    }
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        status.setText(UiText.text("ui.java.duplicate_review_ui_service.1f3a7d510c") + UiExceptionSupport.message(cause));
                        return;
                    }
                    var presentation = presenter.present(suggestions, selected);
                    sourceTitle.setText(presentation.sourceTitle());
                    sourceMeta.setText(presentation.sourceAuthors());
                    sourceArtifacts.setText(presentation.sourceArtifacts());
                    table.setItems(FXCollections.observableArrayList(presentation.rows()));
                    displayed.set(new FuzzyDisplayContext(expectedCollectionId, expectedSourceBookId, generation));
                    status.setText(presentation.rows().isEmpty()
                            ? UiText.text("ui.java.duplicate_review_ui_service.e24097d3a7")
                            : UiText.text("ui.java.duplicate_review_ui_service.99a6c0fe1d") + presentation.rows().size()
                            + UiText.text("ui.java.duplicate_review_ui_service.ff70ac70a1"));
                }));
    }


    private void reviewAndMerge(DuplicateReviewRow row, Label status, Runnable reload,
                                AtomicReference<FuzzyDisplayContext> displayed) {
        FuzzyDisplayContext context = displayed.get();
        if (context == null || row == null || row.candidateId() == null || row.candidateId().isBlank()) return;
        if (!isCurrentBook(context.collectionId(), context.sourceBookId())) {
            displayed.set(null);
            reload.run();
            return;
        }
        if (exactScanner.isRunning()) {
            dialogs.showWarning(UiText.text("ui.java.duplicate_review_ui_service.5b56aa66a8"), UiText.text("ui.java.duplicate_review_ui_service.b3adfac1df"));
            return;
        }
        status.setText(UiText.text("ui.java.duplicate_review_ui_service.36fc43e6f4"));
        background.submitCollectionScoped(LibraryOperationType.QUERY, () -> {
                    requireCurrentCollection(context.collectionId());
                    BookDto source = loadBook.execute(BookId.fromString(context.sourceBookId())).orElse(null);
                    BookDto candidate = loadBook.execute(BookId.fromString(row.candidateId())).orElse(null);
                    return new MergeReviewBooks(source, candidate);
                })
                .whenComplete((books, error) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrentCollection(context.collectionId()) || !Objects.equals(displayed.get(), context)) return;
                    if (error != null) {
                        status.setText(UiText.text("ui.java.duplicate_review_ui_service.0c21524590") + UiExceptionSupport.message(error));
                        return;
                    }
                    if (books == null || books.source() == null || books.candidate() == null) {
                        status.setText(UiText.text("ui.java.duplicate_review_ui_service.6edc922d63"));
                        displayed.set(null);
                        reload.run();
                        return;
                    }
                    confirmMerge(books.source(), books.candidate())
                            .ifPresent(plan -> executeMerge(plan, context.collectionId(), context.sourceBookId(), status, reload));
                }));
    }

    private Optional<BookMergePlan> confirmMerge(BookDto source, BookDto candidate) {
        Dialog<BookMergePlan> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.setTitle(UiText.text("ui.java.duplicate_review_ui_service.dd186f5e83"));
        dialog.setHeaderText(UiText.text("ui.java.duplicate_review_ui_service.6c2bf2de38"));
        ButtonType mergeType = new ButtonType(UiText.text("ui.java.duplicate_review_ui_service.8dbcd06a67"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(mergeType, ButtonType.CANCEL);

        GridPane comparison = new GridPane();
        comparison.setHgap(16);
        comparison.setVgap(7);
        comparison.addRow(0, new Label(""), strong(UiText.text("ui.java.duplicate_review_ui_service.a671efaced")), strong(UiText.text("ui.java.duplicate_review_ui_service.0e4e1b8481")));
        comparison.addRow(1, new Label(UiText.text("ui.bulkedit.field.title")), wrap(source.getTitle()), wrap(candidate.getTitle()));
        comparison.addRow(2, new Label(UiText.text("ui.annotations.digest_author")), wrap(source.getAuthorsText()), wrap(candidate.getAuthorsText()));
        comparison.addRow(3, new Label(UiText.text("ui.bulkedit.field.year")), wrap(value(source.getYear())), wrap(value(candidate.getYear())));
        comparison.addRow(4, new Label(UiText.text("ui.common.isbn")), wrap(value(source.getIsbn())), wrap(value(candidate.getIsbn())));
        comparison.addRow(5, new Label(UiText.text("ui.details.section.files")), wrap(presenter.artifacts(source)), wrap(presenter.artifacts(candidate)));
        comparison.addRow(6, new Label(UiText.text("ui.java.duplicate_review_ui_service.976c268acb")), wrap(source.getRate() + " / " + source.getProgress() + "%"),
                wrap(candidate.getRate() + " / " + candidate.getProgress() + "%"));

        BookChoice sourceChoice = new BookChoice(source.getId(), UiText.text("ui.java.duplicate_review_ui_service.c36b2d0853") + source.getTitle());
        BookChoice candidateChoice = new BookChoice(candidate.getId(), UiText.text("ui.java.duplicate_review_ui_service.4b0bc3f904") + candidate.getTitle());
        ComboBox<BookChoice> survivor = new ComboBox<>(FXCollections.observableArrayList(sourceChoice, candidateChoice));
        survivor.getSelectionModel().select(sourceChoice);
        ComboBox<BookChoice> metadata = new ComboBox<>(FXCollections.observableArrayList(sourceChoice, candidateChoice));
        metadata.getSelectionModel().select(sourceChoice);
        survivor.setMaxWidth(Double.MAX_VALUE);
        metadata.setMaxWidth(Double.MAX_VALUE);

        Label policy = new Label(UiText.text("ui.java.duplicate_review_ui_service.bd0b1d289c"));
        policy.setWrapText(true);
        VBox content = new VBox(12, comparison,
                new Label(UiText.text("ui.java.duplicate_review_ui_service.e32e4deb13")), survivor,
                new Label(UiText.text("ui.java.duplicate_review_ui_service.4dd90eb9a9")), metadata,
                policy);
        content.setPrefWidth(780);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == mergeType
                ? new BookMergePlan(BookId.fromString(survivor.getValue().id()),
                    BookId.fromString(survivor.getValue().id().equals(source.getId()) ? candidate.getId() : source.getId()),
                    BookId.fromString(metadata.getValue().id()))
                : null);
        return dialog.showAndWait();
    }

    private void executeMerge(BookMergePlan plan, String expectedCollectionId, String expectedSourceBookId,
                              Label status, Runnable reload) {
        if (!isCurrentCollection(expectedCollectionId)) return;
        status.setText(UiText.text("ui.java.duplicate_review_ui_service.bb7cd21e20"));
        background.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
            requireCurrentCollection(expectedCollectionId);
            BookMergeResult result = mergeBooks.merge(plan);
            BookDto survivor = loadBook.execute(result.survivorBookId()).orElse(null);
            return new MergeUiResult(result, survivor);
        }).whenComplete((value, error) -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrentCollection(expectedCollectionId)) return;
            if (error != null) {
                status.setText(UiText.text("ui.java.duplicate_review_ui_service.1eac627fb3") + UiExceptionSupport.message(error));
                return;
            }
            if (value.survivor() != null && isCurrentBook(expectedCollectionId, expectedSourceBookId)) {
                appState.getBookDetails().setCurrentBook(value.survivor());
            }
            status.setText(UiText.text("ui.java.duplicate_review_ui_service.f7ee989cc7") + value.result().mergeId()
                    + (value.result().searchIndexSynchronized() ? UiText.text("ui.java.duplicate_review_ui_service.f163fed16a")
                    : UiText.text("ui.java.duplicate_review_ui_service.e12ccbbe50")));
            reload.run();
        }));
    }

    private void undoLatest(Label status, Runnable reload, AtomicReference<FuzzyDisplayContext> displayed) {
        FuzzyDisplayContext context = displayed.get();
        if (context == null || !isCurrentBook(context.collectionId(), context.sourceBookId())) {
            displayed.set(null);
            reload.run();
            return;
        }
        if (exactScanner.isRunning()) {
            dialogs.showWarning(UiText.text("ui.java.duplicate_review_ui_service.136fa4aa18"), UiText.text("ui.java.duplicate_review_ui_service.a257177e71"));
            return;
        }
        String selectedId = context.sourceBookId();
        String expectedCollectionId = context.collectionId();
        status.setText(UiText.text("ui.java.duplicate_review_ui_service.ab9a18a7bc"));
        background.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
            requireCurrentCollection(expectedCollectionId);
            var journal = mergeBooks.latestActiveMerge(BookId.fromString(selectedId))
                    .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.duplicate_review_ui_service.bf9967bb74")));
            BookMergeResult result = mergeBooks.undo(journal.mergeId());
            BookDto restored = loadBook.execute(BookId.fromString(selectedId)).orElse(null);
            return new MergeUiResult(result, restored);
        }).whenComplete((value, error) -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrentCollection(expectedCollectionId)) return;
            if (error != null) {
                status.setText(UiText.text("ui.java.duplicate_review_ui_service.22c2b1d205") + UiExceptionSupport.message(error));
                return;
            }
            if (value.survivor() != null && isCurrentBook(expectedCollectionId, selectedId)) {
                appState.getBookDetails().setCurrentBook(value.survivor());
            }
            status.setText(UiText.text("ui.java.duplicate_review_ui_service.4e38f4437a") + value.result().mergeId()
                    + (value.result().searchIndexSynchronized() ? UiText.text("ui.java.duplicate_review_ui_service.f163fed16a")
                    : UiText.text("ui.java.duplicate_review_ui_service.dd32f90203")));
            reload.run();
        }));
    }

    private boolean isCurrentCollection(String expectedCollectionId) {
        return expectedCollectionId != null
                && Objects.equals(expectedCollectionId, appState.getCurrentLibraryCollectionId());
    }

    private boolean isCurrentBook(String expectedCollectionId, String expectedBookId) {
        if (!isCurrentCollection(expectedCollectionId) || expectedBookId == null || expectedBookId.isBlank()) return false;
        BookDto current = appState.getBookDetails().getCurrentBook();
        return current != null && Objects.equals(expectedBookId, current.getId());
    }

    private void requireCurrentCollection(String expectedCollectionId) {
        if (!isCurrentCollection(expectedCollectionId)) {
            throw new IllegalStateException(UiText.text("ui.error.collection_changed.duplicates"));
        }
    }

    private static Label strong(String value) {
        Label label = wrap(value);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static Label wrap(String value) {
        Label label = new Label(value == null || value.isBlank() ? "—" : value);
        label.setWrapText(true);
        label.setMaxWidth(310);
        return label;
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "—" : value.toString();
    }

    private record BookChoice(String id, String label) {
        @Override public String toString() { return label; }
    }

    private record FuzzyDisplayContext(String collectionId, String sourceBookId, long generation) { }
    private record MergeReviewBooks(BookDto source, BookDto candidate) { }
    private record MergeUiResult(BookMergeResult result, BookDto survivor) {}

    private Tab exactTab() {
        Label status = new Label(UiText.text("ui.java.duplicate_review_ui_service.3d148c2262"));
        status.setWrapText(true);
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        Label counters = new Label("0 / 0");
        TextArea results = new TextArea();
        results.setEditable(false);
        results.setWrapText(false);
        results.setPromptText(UiText.text("ui.java.duplicate_review_ui_service.629f8d87f7"));

        Button start = new Button(UiText.text("ui.java.duplicate_review_ui_service.2f67bac666"));
        Button cancel = new Button(UiText.text("ui.fxml.import_workspace.cancel"));
        cancel.setDisable(!exactScanner.isRunning());
        start.setDisable(exactScanner.isRunning());

        start.setOnAction(event -> startExactScan(start, cancel, status, counters, progress, results));
        cancel.setOnAction(event -> {
            exactScanner.cancel();
            status.setText(UiText.text("ui.java.duplicate_review_ui_service.9fb560a72a"));
        });

        VBox content = new VBox(10,
                new HBox(10, start, cancel), status, progress, counters,
                new Label(UiText.text("ui.java.duplicate_review_ui_service.91b5be80ba")), results);
        content.setPadding(new Insets(12));
        VBox.setVgrow(results, Priority.ALWAYS);
        Tab tab = new Tab(UiText.text("ui.java.duplicate_review_ui_service.e818fee43c"), content);
        tab.setClosable(false);
        return tab;
    }

    private void startExactScan(Button start, Button cancel, Label status, Label counters,
                                ProgressBar progress, TextArea results) {
        var collection = appState.getCurrentLibraryCollection();
        if (collection == null) {
            dialogs.showWarning(UiText.text("ui.fxml.integrity_check.duplicates"), UiText.text("ui.java.duplicate_review_ui_service.6686ed3c16"));
            return;
        }
        start.setDisable(true);
        cancel.setDisable(false);
        results.clear();
        status.setText(UiText.text("ui.java.duplicate_review_ui_service.a809b85f6b"));
        try {
            java.util.concurrent.atomic.AtomicReference<String> operationId = new java.util.concurrent.atomic.AtomicReference<>();
            exactScanner.scanAsync(telemetry -> {
                        operationId.set(telemetry.operationId());
                        operationCenter.accept(UiText.text("ui.java.duplicate_review_ui_service.7bac3520ec"), collection.getId(), telemetry);
                        UiExecutor.runOnUiThread(() -> {
                            if (isCurrentCollection(collection.getId())) applyProgress(telemetry, status, counters, progress);
                        });
                    })
                    .whenComplete((result, error) -> {
                        String id = operationId.get();
                        if (error != null && id != null) {
                            operationCenter.fail(id, UiExceptionSupport.unwrapAsync(error));
                        } else if (result != null && id != null) {
                            // The scanner normally emits COMPLETED/CANCELLED telemetry itself. Keep the
                            // journal terminal even if a future implementation returns before that final event.
                            operationCenter.snapshot().stream()
                                    .filter(entry -> id.equals(entry.operationId()) && entry.active())
                                    .findFirst()
                                    .ifPresent(entry -> {
                                        if (result.cancelled()) operationCenter.cancel(id, UiText.text("ui.java.duplicate_review_ui_service.2553d725bc"));
                                        else operationCenter.complete(id, UiText.text("ui.java.duplicate_review_ui_service.7f710ec992") + result.groups().size());
                                    });
                        }
                        UiExecutor.runOnUiThread(() -> {
                            start.setDisable(false);
                            cancel.setDisable(true);
                            if (!isCurrentCollection(collection.getId())) return;
                            if (error != null) {
                                Throwable cause = UiExceptionSupport.unwrapAsync(error);
                                status.setText(UiText.text("ui.java.duplicate_review_ui_service.13820cb274") + UiExceptionSupport.message(cause));
                                return;
                            }
                            renderExactResult(result, status, counters, progress, results);
                        });
                    });
        } catch (RuntimeException error) {
            start.setDisable(false);
            cancel.setDisable(true);
            status.setText(UiText.text("ui.java.duplicate_review_ui_service.0fd8afea33") + UiExceptionSupport.message(error));
        }
    }

    private static void applyProgress(OperationProgress value, Label status, Label counters, ProgressBar progress) {
        long total = Math.max(0, value.total());
        long processed = Math.max(0, value.processed());
        progress.setProgress(total <= 0 ? ProgressIndicator.INDETERMINATE_PROGRESS : value.fraction());
        counters.setText(NumberFormat.getIntegerInstance(Locale.getDefault()).format(processed)
                + " / " + NumberFormat.getIntegerInstance(Locale.getDefault()).format(total));
        status.setText(UiText.text("ui.java.duplicate_review_ui_service.384f693f20") + operationStageText(value.stage()) + (value.currentItem().isBlank() ? "" : " · " + value.currentItem()));
    }

    private static String operationStageText(com.myhomelibcorp.application.progress.OperationStage stage) {
        if (stage == null) return "—";
        return switch (stage) {
            case CHECKING_SERVER -> UiText.text("ui.java.duplicate_review_ui_service.d5cb0c3a91");
            case IMPORTING -> UiText.text("ui.import.action.generic");
            case ROLLING_BACK -> UiText.text("ui.java.duplicate_review_ui_service.d0f715afcd");
            case REFRESHING_STATISTICS -> UiText.text("ui.java.duplicate_review_ui_service.7c96a35ead");
            case OPTIMIZING_DATABASE -> UiText.text("ui.java.duplicate_review_ui_service.bd69af2a4d");
            case BACKING_UP -> UiText.text("ui.backup.confirm.start.title");
            case FINALIZING -> UiText.text("ui.fxml.operation_center_workspace.finalizing");
            case FAILED -> UiText.text("common.error");
            default -> OperationStageText.text(stage);
        };
    }


    private static void renderExactResult(ExactDuplicateScanResult result, Label status, Label counters,
                                          ProgressBar progress, TextArea results) {
        if (result == null) return;
        progress.setProgress(result.total() == 0 ? 1.0 : Math.min(1.0, (double) result.processed() / result.total()));
        counters.setText(result.processed() + " / " + result.total());
        if (result.cancelled()) {
            status.setText(UiText.text("ui.java.duplicate_review_ui_service.69c903c2bb") + result.processed() + UiText.text("ui.java.duplicate_review_ui_service.5a37634d69") + result.total()
                    + UiText.text("ui.java.duplicate_review_ui_service.d5600fa21d") + result.scanId() + ".");
            return;
        }
        status.setText(UiText.text("ui.java.duplicate_review_ui_service.13c279bfac") + result.groups().size() + UiText.text("ui.java.duplicate_review_ui_service.d2f0a5e078")
                + result.duplicateArtifacts() + UiText.text("ui.java.duplicate_review_ui_service.e9c2512251") + result.skipped() + ".");
        results.setText(formatExactGroups(result.groups()));
    }

    static String formatExactGroups(List<ExactDuplicateGroup> groups) {
        if (groups == null || groups.isEmpty()) return UiText.text("ui.java.duplicate_review_ui_service.994406e058");
        StringBuilder out = new StringBuilder();
        int index = 1;
        for (ExactDuplicateGroup group : groups) {
            out.append("#").append(index++).append(" SHA-256 ").append(group.sha256()).append('\n');
            group.artifacts().forEach(artifact -> out.append("  • ")
                    .append(artifact.title()).append(" — ")
                    .append(artifact.format().toUpperCase(Locale.ROOT)).append(" — ")
                    .append(artifact.file().getFileName()).append(UiText.text("ui.java.duplicate_review_ui_service.f470563a9e"))
                    .append(artifact.bookId().asString()).append(")\n"));
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static TableColumn<DuplicateReviewRow, String> column(
            String title, java.util.function.Function<DuplicateReviewRow, String> getter, double width) {
        TableColumn<DuplicateReviewRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(getter.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }
}
