package com.myhomelibcorp.ui.duplicate;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.duplicate.ExactDuplicateGroup;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanResult;
import com.myhomelibcorp.application.duplicate.merge.BookMergePlan;
import com.myhomelibcorp.application.duplicate.merge.BookMergeResult;
import com.myhomelibcorp.application.progress.OperationProgress;
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
            dialogs.showWarning("Дублікати", "Спочатку виберіть колекцію.");
            return;
        }
        Stage stage = new Stage();
        stage.setTitle("Дублікати книг і файлів");
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
        Label status = new Label("Fuzzy detector лише пропонує кандидати. Злиття можливе тільки після окремого ручного підтвердження; файли на диску не видаляються.");
        status.setWrapText(true);

        TableView<DuplicateReviewRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(column("Кандидат", DuplicateReviewRow::candidateTitle, 230));
        table.getColumns().add(column("Автор", DuplicateReviewRow::candidateAuthors, 180));
        table.getColumns().add(column("Рік", DuplicateReviewRow::candidateYear, 70));
        table.getColumns().add(column("ISBN", DuplicateReviewRow::candidateIsbn, 130));
        table.getColumns().add(column("Score", DuplicateReviewRow::score, 85));
        table.getColumns().add(column("Причини", DuplicateReviewRow::reasons, 260));
        table.getColumns().add(column("Artifacts / стан", DuplicateReviewRow::artifacts, 300));
        table.setPlaceholder(new Label("Немає пропозицій для перегляду."));

        ProgressIndicator loading = new ProgressIndicator();
        loading.setPrefSize(24, 24);
        loading.setVisible(false);
        Button refresh = new Button("Перевірити вибрану книгу");
        Button merge = new Button("Переглянути та злити…");
        Button undo = new Button("Скасувати останнє злиття");
        merge.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> merge.setDisable(row == null));
        HBox actions = new HBox(10, refresh, merge, undo, loading);

        Runnable load = () -> loadFuzzy(sourceTitle, sourceMeta, sourceArtifacts, status, table, refresh, loading);
        refresh.setOnAction(event -> load.run());
        merge.setOnAction(event -> reviewAndMerge(table.getSelectionModel().getSelectedItem(), status, load));
        undo.setOnAction(event -> undoLatest(status, load));
        Platform.runLater(load);

        VBox content = new VBox(8,
                new Label("Вихідна книга:"), sourceTitle, sourceMeta,
                new Label("Artifacts вихідної книги:"), sourceArtifacts,
                new Separator(), actions, status, table);
        content.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        Tab tab = new Tab("Схожі книги", content);
        tab.setClosable(false);
        return tab;
    }

    private void loadFuzzy(Label sourceTitle, Label sourceMeta, Label sourceArtifacts, Label status,
                           TableView<DuplicateReviewRow> table, Button refresh, ProgressIndicator loading) {
        BookDto selected = appState.getBookDetails().getCurrentBook();
        if (selected == null || selected.getId() == null || selected.getId().isBlank()) {
            sourceTitle.setText("Книгу не вибрано");
            sourceMeta.setText("Виберіть книгу в бібліотеці та натисніть «Перевірити вибрану книгу».");
            sourceArtifacts.setText("—");
            table.setItems(FXCollections.observableArrayList());
            return;
        }
        sourceTitle.setText(selected.getTitle());
        sourceMeta.setText(selected.getAuthorsText() == null ? "" : selected.getAuthorsText());
        sourceArtifacts.setText(presenter.artifacts(selected));
        status.setText("Пошук bounded-кандидатів… Автоматичного злиття немає.");
        refresh.setDisable(true);
        loading.setVisible(true);

        background.submit(() -> fuzzyReview.review(BookId.fromString(selected.getId()), ReviewBookDuplicatesUseCase.DEFAULT_LIMIT))
                .whenComplete((suggestions, error) -> UiExecutor.runOnUiThread(() -> {
                    refresh.setDisable(false);
                    loading.setVisible(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        status.setText("Не вдалося виконати fuzzy review: " + cause.getMessage());
                        return;
                    }
                    var presentation = presenter.present(suggestions, selected);
                    sourceTitle.setText(presentation.sourceTitle());
                    sourceMeta.setText(presentation.sourceAuthors());
                    sourceArtifacts.setText(presentation.sourceArtifacts());
                    table.setItems(FXCollections.observableArrayList(presentation.rows()));
                    status.setText(presentation.rows().isEmpty()
                            ? "Схожих книг вище порогу не знайдено."
                            : "Знайдено пропозицій: " + presentation.rows().size()
                            + ". Перевірте score, причини й artifacts; merge вимагає окремого підтвердження.");
                }));
    }


    private void reviewAndMerge(DuplicateReviewRow row, Label status, Runnable reload) {
        BookDto source = appState.getBookDetails().getCurrentBook();
        if (source == null || row == null || row.candidateId() == null || row.candidateId().isBlank()) return;
        if (exactScanner.isRunning()) {
            dialogs.showWarning("Злиття книг", "Спочатку завершіть або скасуйте SHA-256 scan. Merge змінює ownership artifacts.");
            return;
        }
        status.setText("Завантаження обох книг для side-by-side review…");
        background.submit(() -> loadBook.execute(BookId.fromString(row.candidateId())))
                .whenComplete((candidate, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        status.setText("Не вдалося завантажити кандидата: " + UiExceptionSupport.unwrapAsync(error).getMessage());
                        return;
                    }
                    if (candidate == null || candidate.isEmpty()) {
                        status.setText("Кандидат уже недоступний. Оновіть список.");
                        reload.run();
                        return;
                    }
                    confirmMerge(source, candidate.get()).ifPresent(plan -> executeMerge(plan, status, reload));
                }));
    }

    private Optional<BookMergePlan> confirmMerge(BookDto source, BookDto candidate) {
        Dialog<BookMergePlan> dialog = new Dialog<>();
        dialog.setTitle("Підтвердження злиття книг");
        dialog.setHeaderText("Side-by-side review. Це логічне злиття БД; фізичні файли не видаляються.");
        ButtonType mergeType = new ButtonType("Злити без видалення файлів", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(mergeType, ButtonType.CANCEL);

        GridPane comparison = new GridPane();
        comparison.setHgap(16);
        comparison.setVgap(7);
        comparison.addRow(0, new Label(""), strong("Поточна книга"), strong("Кандидат"));
        comparison.addRow(1, new Label("Назва"), wrap(source.getTitle()), wrap(candidate.getTitle()));
        comparison.addRow(2, new Label("Автор"), wrap(source.getAuthorsText()), wrap(candidate.getAuthorsText()));
        comparison.addRow(3, new Label("Рік"), wrap(value(source.getYear())), wrap(value(candidate.getYear())));
        comparison.addRow(4, new Label("ISBN"), wrap(value(source.getIsbn())), wrap(value(candidate.getIsbn())));
        comparison.addRow(5, new Label("Artifacts"), wrap(presenter.artifacts(source)), wrap(presenter.artifacts(candidate)));
        comparison.addRow(6, new Label("Оцінка / прогрес"), wrap(source.getRate() + " / " + source.getProgress() + "%"),
                wrap(candidate.getRate() + " / " + candidate.getProgress() + "%"));

        BookChoice sourceChoice = new BookChoice(source.getId(), "Поточна: " + source.getTitle());
        BookChoice candidateChoice = new BookChoice(candidate.getId(), "Кандидат: " + candidate.getTitle());
        ComboBox<BookChoice> survivor = new ComboBox<>(FXCollections.observableArrayList(sourceChoice, candidateChoice));
        survivor.getSelectionModel().select(sourceChoice);
        ComboBox<BookChoice> metadata = new ComboBox<>(FXCollections.observableArrayList(sourceChoice, candidateChoice));
        metadata.getSelectionModel().select(sourceChoice);
        survivor.setMaxWidth(Double.MAX_VALUE);
        metadata.setMaxWidth(Double.MAX_VALUE);

        Label policy = new Label("Збереження user data: authors/genres/groups/keywords об’єднуються; bookmarks і artifacts переходять survivor-у; reading state зводиться детерміновано. Undo зберігається в БД.");
        policy.setWrapText(true);
        VBox content = new VBox(12, comparison,
                new Label("Яку книгу залишити:"), survivor,
                new Label("З якої книги взяти бібліографічні поля:"), metadata,
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

    private void executeMerge(BookMergePlan plan, Label status, Runnable reload) {
        status.setText("Транзакційне злиття…");
        background.submit(() -> {
            BookMergeResult result = mergeBooks.merge(plan);
            BookDto survivor = loadBook.execute(result.survivorBookId()).orElse(null);
            return new MergeUiResult(result, survivor);
        }).whenComplete((value, error) -> UiExecutor.runOnUiThread(() -> {
            if (error != null) {
                status.setText("Merge відхилено/не виконано: " + UiExceptionSupport.unwrapAsync(error).getMessage());
                return;
            }
            if (value.survivor() != null) appState.getBookDetails().setCurrentBook(value.survivor());
            status.setText("Злиття виконано. Merge ID: " + value.result().mergeId()
                    + (value.result().searchIndexSynchronized() ? ". Пошуковий індекс синхронізовано."
                    : ". БД commit виконано, але пошуковий індекс потребує rebuild."));
            reload.run();
        }));
    }

    private void undoLatest(Label status, Runnable reload) {
        BookDto selected = appState.getBookDetails().getCurrentBook();
        if (selected == null || selected.getId() == null || selected.getId().isBlank()) {
            dialogs.showWarning("Undo merge", "Виберіть книгу, для якої потрібно знайти останнє злиття.");
            return;
        }
        if (exactScanner.isRunning()) {
            dialogs.showWarning("Undo merge", "Спочатку завершіть або скасуйте SHA-256 scan.");
            return;
        }
        String selectedId = selected.getId();
        status.setText("Пошук останнього активного merge journal…");
        background.submit(() -> {
            var journal = mergeBooks.latestActiveMerge(BookId.fromString(selectedId))
                    .orElseThrow(() -> new IllegalStateException("Активного merge для вибраної книги немає"));
            BookMergeResult result = mergeBooks.undo(journal.mergeId());
            BookDto restored = loadBook.execute(BookId.fromString(selectedId)).orElse(null);
            return new MergeUiResult(result, restored);
        }).whenComplete((value, error) -> UiExecutor.runOnUiThread(() -> {
            if (error != null) {
                status.setText("Undo не виконано: " + UiExceptionSupport.unwrapAsync(error).getMessage());
                return;
            }
            if (value.survivor() != null) appState.getBookDetails().setCurrentBook(value.survivor());
            status.setText("Злиття скасовано: " + value.result().mergeId()
                    + (value.result().searchIndexSynchronized() ? ". Пошуковий індекс синхронізовано."
                    : ". БД відновлено, але пошуковий індекс потребує rebuild."));
            reload.run();
        }));
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

    private record MergeUiResult(BookMergeResult result, BookDto survivor) {}

    private Tab exactTab() {
        Label status = new Label("SHA-256 scan порівнює повний вміст локальних artifacts.");
        status.setWrapText(true);
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        Label counters = new Label("0 / 0");
        TextArea results = new TextArea();
        results.setEditable(false);
        results.setWrapText(false);
        results.setPromptText("Тут з'являться групи byte-for-byte дублікатів.");

        Button start = new Button("Запустити / продовжити scan");
        Button cancel = new Button("Скасувати");
        cancel.setDisable(!exactScanner.isRunning());
        start.setDisable(exactScanner.isRunning());

        start.setOnAction(event -> startExactScan(start, cancel, status, counters, progress, results));
        cancel.setOnAction(event -> {
            exactScanner.cancel();
            status.setText("Запит на скасування надіслано. Snapshot буде збережено для resume.");
        });

        VBox content = new VBox(10,
                new HBox(10, start, cancel), status, progress, counters,
                new Label("Групи точних дублікатів:"), results);
        content.setPadding(new Insets(12));
        VBox.setVgrow(results, Priority.ALWAYS);
        Tab tab = new Tab("Точні файли (SHA-256)", content);
        tab.setClosable(false);
        return tab;
    }

    private void startExactScan(Button start, Button cancel, Label status, Label counters,
                                ProgressBar progress, TextArea results) {
        var collection = appState.getCurrentLibraryCollection();
        if (collection == null) {
            dialogs.showWarning("Дублікати", "Спочатку виберіть колекцію.");
            return;
        }
        start.setDisable(true);
        cancel.setDisable(false);
        results.clear();
        status.setText("Сканування локальних artifacts…");
        try {
            java.util.concurrent.atomic.AtomicReference<String> operationId = new java.util.concurrent.atomic.AtomicReference<>();
            exactScanner.scanAsync(telemetry -> {
                        operationId.set(telemetry.operationId());
                        operationCenter.accept("Пошук точних дублікатів", collection.getId(), telemetry);
                        UiExecutor.runOnUiThread(() -> applyProgress(telemetry, status, counters, progress));
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
                                        if (result.cancelled()) operationCenter.cancel(id, "Пошук дублікатів скасовано");
                                        else operationCenter.complete(id, "Груп знайдено: " + result.groups().size());
                                    });
                        }
                        UiExecutor.runOnUiThread(() -> {
                            start.setDisable(false);
                            cancel.setDisable(true);
                            if (error != null) {
                                Throwable cause = UiExceptionSupport.unwrapAsync(error);
                                status.setText("Помилка scan: " + cause.getMessage());
                                return;
                            }
                            renderExactResult(result, status, counters, progress, results);
                        });
                    });
        } catch (RuntimeException error) {
            start.setDisable(false);
            cancel.setDisable(true);
            status.setText("Не вдалося запустити scan: " + error.getMessage());
        }
    }

    private static void applyProgress(OperationProgress value, Label status, Label counters, ProgressBar progress) {
        long total = Math.max(0, value.total());
        long processed = Math.max(0, value.processed());
        progress.setProgress(total <= 0 ? ProgressIndicator.INDETERMINATE_PROGRESS : value.fraction());
        counters.setText(NumberFormat.getIntegerInstance(Locale.getDefault()).format(processed)
                + " / " + NumberFormat.getIntegerInstance(Locale.getDefault()).format(total));
        status.setText("Стан: " + value.stage() + (value.currentItem().isBlank() ? "" : " · " + value.currentItem()));
    }

    private static void renderExactResult(ExactDuplicateScanResult result, Label status, Label counters,
                                          ProgressBar progress, TextArea results) {
        if (result == null) return;
        progress.setProgress(result.total() == 0 ? 1.0 : Math.min(1.0, (double) result.processed() / result.total()));
        counters.setText(result.processed() + " / " + result.total());
        if (result.cancelled()) {
            status.setText("Scan призупинено. Оброблено " + result.processed() + " з " + result.total()
                    + "; наступний запуск продовжить snapshot " + result.scanId() + ".");
            return;
        }
        status.setText("Scan завершено: груп " + result.groups().size() + ", duplicate artifacts "
                + result.duplicateArtifacts() + ", skipped " + result.skipped() + ".");
        results.setText(formatExactGroups(result.groups()));
    }

    static String formatExactGroups(List<ExactDuplicateGroup> groups) {
        if (groups == null || groups.isEmpty()) return "Точних byte-for-byte дублікатів не знайдено.";
        StringBuilder out = new StringBuilder();
        int index = 1;
        for (ExactDuplicateGroup group : groups) {
            out.append("#").append(index++).append(" SHA-256 ").append(group.sha256()).append('\n');
            group.artifacts().forEach(artifact -> out.append("  • ")
                    .append(artifact.title()).append(" — ")
                    .append(artifact.format().toUpperCase(Locale.ROOT)).append(" — ")
                    .append(artifact.file().getFileName()).append(" (book ")
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
