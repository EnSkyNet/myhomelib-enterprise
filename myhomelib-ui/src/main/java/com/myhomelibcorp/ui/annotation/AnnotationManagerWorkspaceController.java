package com.myhomelibcorp.ui.annotation;

import com.myhomelibcorp.application.annotation.AnnotationBatchUndoToken;
import com.myhomelibcorp.application.annotation.AnnotationManagerFacets;
import com.myhomelibcorp.application.annotation.AnnotationManagerFilter;
import com.myhomelibcorp.application.annotation.AnnotationManagerItem;
import com.myhomelibcorp.application.annotation.AnnotationManagerPage;
import com.myhomelibcorp.application.annotation.AnnotationManagerService;
import com.myhomelibcorp.application.annotation.AnnotationManagerType;
import com.myhomelibcorp.application.annotation.export.AnnotationExportFormat;
import com.myhomelibcorp.application.annotation.export.AnnotationExportRequest;
import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;
import com.myhomelibcorp.application.annotation.export.AnnotationExportService;
import com.myhomelibcorp.application.annotation.export.AnnotationExportTemplates;
import com.myhomelibcorp.application.annotation.knowledge.AnnotationDigestLabels;
import com.myhomelibcorp.application.annotation.knowledge.AnnotationDigestService;
import com.myhomelibcorp.application.annotation.knowledge.KnowledgeMarkdownExportRequest;
import com.myhomelibcorp.application.annotation.knowledge.KnowledgeMarkdownExportService;
import com.myhomelibcorp.application.annotation.knowledge.KnowledgeMarkdownExportTemplates;
import com.myhomelibcorp.application.annotation.knowledge.KnowledgeMarkdownReExportPolicy;
import com.myhomelibcorp.application.annotation.AnnotationService;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Global, paged Annotation Manager workspace (MHL-204). */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
public class AnnotationManagerWorkspaceController implements WorkspaceLifecycle {
    private static final int PAGE_SIZE = 100;
    private static final int EXPORT_PAGE_SIZE = 500;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnnotationManagerService annotationManagerService;
    private final AnnotationEditorDialog annotationEditorDialog;
    private final AnnotationExportService annotationExportService;
    private final KnowledgeMarkdownExportService knowledgeMarkdownExportService;
    private final AnnotationDigestService annotationDigestService;
    private final WorkspaceManager workspaceManager;
    private final UiBackgroundExecutor executor;
    private final DialogService dialogs;
    private final FileChooserService fileChooserService;
    private final LocalizationService i18n;

    @FXML private BorderPane root;
    @FXML private TextField searchField;
    @FXML private ComboBox<BookChoice> bookFilter;
    @FXML private ComboBox<ValueChoice<AnnotationManagerType>> typeFilter;
    @FXML private ComboBox<ValueChoice<String>> colorFilter;
    @FXML private ComboBox<ValueChoice<String>> tagFilter;
    @FXML private DatePicker dateFromFilter;
    @FXML private DatePicker dateToFilter;
    @FXML private TableView<AnnotationManagerItem> table;
    @FXML private TableColumn<AnnotationManagerItem, String> typeColumn;
    @FXML private TableColumn<AnnotationManagerItem, String> bookColumn;
    @FXML private TableColumn<AnnotationManagerItem, String> textColumn;
    @FXML private TableColumn<AnnotationManagerItem, String> chapterColumn;
    @FXML private TableColumn<AnnotationManagerItem, String> tagsColumn;
    @FXML private TableColumn<AnnotationManagerItem, String> updatedColumn;
    @FXML private Label statusLabel;
    @FXML private Label selectedLabel;
    @FXML private Label pageLabel;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button openButton;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private Button undoButton;
    @FXML private Button batchColorButton;
    @FXML private Button batchTagsButton;
    @FXML private Button batchRemoveTagsButton;
    @FXML private Button exportSelectedButton;
    @FXML private Button previousButton;
    @FXML private Button nextButton;

    private final AtomicLong loadGeneration = new AtomicLong();
    private volatile boolean disposed;
    private int offset;
    private AnnotationManagerPage currentPage = new AnnotationManagerPage(List.of(), 0, 0, PAGE_SIZE);
    private static final int MAX_UNDO_OPERATIONS = 20;
    private final Deque<AnnotationBatchUndoToken> undoStack = new ArrayDeque<>();

    @FXML
    public void initialize() {
        configureTable();
        configureFilters();
        table.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<AnnotationManagerItem>) change -> updateActions());
        table.setRowFactory(view -> {
            TableRow<AnnotationManagerItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) openSelected();
            });
            return row;
        });
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.Z && event.isShortcutDown() && !undoStack.isEmpty()
                    && (progressIndicator == null || !progressIndicator.isVisible())) {
                undoDelete();
                event.consume();
            }
        });
        loadFacetsAndPage();
    }

    private void configureTable() {
        // JavaFX 21 exposes this as a Callback constant; FXMLLoader cannot coerce its symbolic name
        // from a plain FXML string. Configure it in Java so the workspace loads on real JavaFX runtimes.
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        typeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(typeLabel(cell.getValue().type())));
        bookColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().bookTitle()));
        textColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(summaryText(cell.getValue())));
        chapterColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().chapterTitle()));
        tagsColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(String.join(", ", cell.getValue().tags())));
        updatedColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                DATE_TIME.format(cell.getValue().updatedAt().atZone(ZoneId.systemDefault()))));
    }

    private void configureFilters() {
        bookFilter.setPromptText(i18n.text("ui.annotations.book_filter"));
        typeFilter.setPromptText(i18n.text("ui.annotations.type_filter"));
        colorFilter.setPromptText(i18n.text("ui.annotations.color_filter"));
        tagFilter.setPromptText(i18n.text("ui.annotations.tag_filter"));
        dateFromFilter.setPromptText(i18n.text("ui.annotations.date_from"));
        dateToFilter.setPromptText(i18n.text("ui.annotations.date_to"));
        typeFilter.setItems(FXCollections.observableArrayList(List.of(
                new ValueChoice<AnnotationManagerType>(null, i18n.text("ui.annotations.all_types")),
                new ValueChoice<>(AnnotationManagerType.HIGHLIGHT, i18n.text("ui.annotations.type.highlight")),
                new ValueChoice<>(AnnotationManagerType.NOTE, i18n.text("ui.annotations.type.note")))));
        typeFilter.getSelectionModel().selectFirst();
    }

    @FXML
    public void refresh() {
        loadFacetsAndPage();
    }

    @FXML
    public void applyFilters() {
        offset = 0;
        loadPage();
    }

    @FXML
    public void clearFilters() {
        searchField.clear();
        if (!bookFilter.getItems().isEmpty()) bookFilter.getSelectionModel().selectFirst();
        if (!typeFilter.getItems().isEmpty()) typeFilter.getSelectionModel().selectFirst();
        if (!colorFilter.getItems().isEmpty()) colorFilter.getSelectionModel().selectFirst();
        if (!tagFilter.getItems().isEmpty()) tagFilter.getSelectionModel().selectFirst();
        dateFromFilter.setValue(null);
        dateToFilter.setValue(null);
        offset = 0;
        loadPage();
    }

    @FXML
    public void previousPage() {
        offset = Math.max(0, offset - PAGE_SIZE);
        loadPage();
    }

    @FXML
    public void nextPage() {
        if (!currentPage.hasNext()) return;
        offset += PAGE_SIZE;
        loadPage();
    }

    @FXML
    public void openSelected() {
        List<AnnotationManagerItem> selectedItems = selectedItems();
        if (selectedItems.size() != 1) return;
        AnnotationManagerItem selected = selectedItems.getFirst();
        workspaceManager.showAnnotationInReader(selected.bookId(), selected.id());
    }

    @FXML
    public void editSelected() {
        List<AnnotationManagerItem> selectedItems = selectedItems();
        if (selectedItems.size() != 1) return;
        AnnotationManagerItem selected = selectedItems.getFirst();
        Stage owner = root.getScene() != null && root.getScene().getWindow() instanceof Stage stage ? stage : null;
        AnnotationEditorDialog.Result edited = annotationEditorDialog.showEdit(
                owner, selected.type(), selected.quote(), selected.note(), selected.color(),
                Set.copyOf(selected.tags()), knownTags()).orElse(null);
        if (edited == null) return;
        setBusy(true, i18n.text("ui.annotations.saving"));
        executor.submit(() -> {
            annotationManagerService.update(selected.id(), edited.note(), edited.color(), edited.tags());
            return null;
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (disposed) return;
            if (error != null) {
                setBusy(false, i18n.text("ui.annotations.save_failed") + ": " + UiExceptionSupport.message(error));
                dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.save_failed"), UiExceptionSupport.message(error));
                return;
            }
            loadFacetsAndPage();
        }));
    }

    @FXML
    public void deleteSelected() {
        List<AnnotationManagerItem> selected = selectedItems();
        if (selected.isEmpty()) return;
        String confirmation = selected.size() == 1
                ? selected.getFirst().bookTitle() + " — " + summaryText(selected.getFirst())
                : i18n.format("ui.annotations.delete_confirm_many", selected.size());
        if (!dialogs.showConfirmation(i18n.text("ui.annotations.delete"),
                i18n.text("ui.annotations.delete_confirm"), confirmation)) return;
        List<String> ids = selected.stream().map(AnnotationManagerItem::id).toList();
        setBusy(true, i18n.text("ui.annotations.deleting"));
        executor.submit(() -> annotationManagerService.deleteForUndo(ids))
                .whenComplete((token, error) -> Platform.runLater(() -> {
                    if (disposed) return;
                    if (error != null) {
                        setBusy(false, i18n.text("ui.annotations.delete_failed") + ": " + UiExceptionSupport.message(error));
                        dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.delete_failed"), UiExceptionSupport.message(error));
                        return;
                    }
                    pushUndo(token);
                    if (currentPage.items().size() <= selected.size() && offset > 0) offset = Math.max(0, offset - PAGE_SIZE);
                    loadFacetsAndPage();
                }));
    }

    @FXML
    public void undoDelete() {
        AnnotationBatchUndoToken token = undoStack.peekFirst();
        if (token == null) return;
        setBusy(true, i18n.text("ui.annotations.restoring"));
        executor.submit(() -> {
            annotationManagerService.restoreDeleted(token);
            return null;
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (disposed) return;
            if (error != null) {
                setBusy(false, i18n.text("ui.annotations.undo_failed") + ": " + UiExceptionSupport.message(error));
                dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.undo_failed"), UiExceptionSupport.message(error));
                return;
            }
            undoStack.removeFirst();
            loadFacetsAndPage();
        }));
    }

    @FXML
    public void changeSelectedColor() {
        List<AnnotationManagerItem> selected = selectedItems();
        if (selected.isEmpty()) return;
        ColorPicker picker = new ColorPicker(parseColor(selected.getFirst().color()));
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.annotations.batch_color"));
        dialog.getDialogPane().getButtonTypes().setAll(
                new ButtonType(i18n.text("common.save"), ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        dialog.getDialogPane().setContent(picker);
        dialog.setResultConverter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE
                ? colorHex(picker.getValue()) : null);
        String color = dialog.showAndWait().orElse(null);
        if (color == null) return;
        List<String> ids = selected.stream().map(AnnotationManagerItem::id).toList();
        setBusy(true, i18n.text("ui.annotations.saving"));
        executor.submit(() -> annotationManagerService.updateColor(ids, color))
                .whenComplete((count, error) -> Platform.runLater(() -> finishBatchEdit(error, count, "ui.annotations.batch_updated")));
    }

    @FXML
    public void addTagsToSelected() {
        List<AnnotationManagerItem> selected = selectedItems();
        if (selected.isEmpty()) return;
        String value = dialogs.showTextInput(i18n.text("ui.annotations.batch_tags"),
                i18n.text("ui.annotations.batch_tags_header"), i18n.text("ui.annotations.edit.tags"), "")
                .orElse(null);
        Set<String> tags = AnnotationEditorDialog.parseTags(value);
        if (tags.isEmpty()) return;
        List<String> ids = selected.stream().map(AnnotationManagerItem::id).toList();
        setBusy(true, i18n.text("ui.annotations.saving"));
        executor.submit(() -> annotationManagerService.addTags(ids, tags))
                .whenComplete((count, error) -> Platform.runLater(() -> finishBatchEdit(error, count, "ui.annotations.batch_updated")));
    }

    @FXML
    public void removeTagsFromSelected() {
        List<AnnotationManagerItem> selected = selectedItems();
        if (selected.isEmpty()) return;
        String value = dialogs.showTextInput(i18n.text("ui.annotations.batch_remove_tags"),
                i18n.text("ui.annotations.batch_remove_tags_header"), i18n.text("ui.annotations.edit.tags"), "")
                .orElse(null);
        Set<String> tags = AnnotationEditorDialog.parseTags(value);
        if (tags.isEmpty()) return;
        List<String> ids = selected.stream().map(AnnotationManagerItem::id).toList();
        setBusy(true, i18n.text("ui.annotations.saving"));
        executor.submit(() -> annotationManagerService.removeTags(ids, tags))
                .whenComplete((count, error) -> Platform.runLater(() -> finishBatchEdit(error, count, "ui.annotations.batch_updated")));
    }

    @FXML
    public void exportDigest() {
        Stage owner = root.getScene() != null && root.getScene().getWindow() instanceof Stage stage ? stage : null;
        java.io.File file = fileChooserService.chooseFileToSave(owner,
                i18n.text("ui.annotations.digest"), "annotations-digest.md");
        if (file == null) return;

        List<AnnotationManagerItem> selected = selectedItems();
        if (!selected.isEmpty()) {
            Path destination = file.toPath();
            setBusy(true, i18n.text("ui.annotations.exporting"));
            executor.submit(() -> exportSelectedDigest(selected, destination))
                    .whenComplete((count, error) -> Platform.runLater(() -> finishDigestExport(destination, count, error)));
            return;
        }

        BookChoice book = bookFilter == null ? null : bookFilter.getValue();
        AnnotationExportSelection selection = book != null && book.id() != null
                ? AnnotationExportSelection.books(Set.of(book.id()))
                : AnnotationExportSelection.all();
        Path destination = file.toPath();
        setBusy(true, i18n.text("ui.annotations.exporting"));
        AnnotationDigestLabels labels = new AnnotationDigestLabels(
                i18n.text("ui.annotations.digest_title"),
                i18n.text("ui.annotations.digest_author"),
                i18n.text("ui.annotations.digest_no_chapter"),
                i18n.text("ui.annotations.digest_quote"),
                i18n.text("ui.annotations.digest_highlight"),
                i18n.text("ui.reader.annotation.editor.note"),
                i18n.text("ui.reader.annotation.editor.tags"),
                i18n.text("ui.annotations.digest_open"));
        executor.submit(() -> annotationDigestService.export(selection, destination, labels))
                .whenComplete((count, error) -> Platform.runLater(() -> finishDigestExport(destination, count, error)));
    }

    private void finishDigestExport(Path destination, Long count, Throwable error) {
        if (disposed) return;
        if (error != null) {
            setBusy(false, i18n.text("ui.annotations.export_failed") + ": " + UiExceptionSupport.message(error));
            dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.export_failed"), UiExceptionSupport.message(error));
            return;
        }
        setBusy(false, i18n.format("ui.annotations.digest_exported", count == null ? 0 : count, destination));
    }

    @FXML
    public void exportSelectedCsv() {
        List<AnnotationManagerItem> selected = selectedItems();
        if (selected.isEmpty()) return;
        Stage owner = root.getScene() != null && root.getScene().getWindow() instanceof Stage stage ? stage : null;
        java.io.File file = fileChooserService.chooseFileToSave(owner,
                i18n.text("ui.annotations.export_csv"), "annotations-selected.csv");
        if (file == null) return;
        Path destination = file.toPath();
        setBusy(true, i18n.text("ui.annotations.exporting"));
        executor.submit(() -> exportSelectedRows(selected, destination)).whenComplete((count, error) -> Platform.runLater(() -> {
            if (disposed) return;
            if (error != null) {
                setBusy(false, i18n.text("ui.annotations.export_failed") + ": " + UiExceptionSupport.message(error));
                dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.export_failed"), UiExceptionSupport.message(error));
                return;
            }
            setBusy(false, i18n.format("ui.annotations.exported", count, destination));
        }));
    }

    @FXML
    public void exportFilteredCsv() {
        Stage owner = root.getScene() != null && root.getScene().getWindow() instanceof Stage stage ? stage : null;
        java.io.File file = fileChooserService.chooseFileToSave(owner,
                i18n.text("ui.annotations.export_csv"), "annotations.csv");
        if (file == null) return;
        AnnotationManagerFilter filter = currentFilter();
        if (filter == null) return;
        Path destination = file.toPath();
        setBusy(true, i18n.text("ui.annotations.exporting"));
        executor.submit(() -> exportCsv(filter, destination)).whenComplete((count, error) -> Platform.runLater(() -> {
            if (disposed) return;
            if (error != null) {
                setBusy(false, i18n.text("ui.annotations.export_failed") + ": " + UiExceptionSupport.message(error));
                dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.export_failed"), UiExceptionSupport.message(error));
                return;
            }
            setBusy(false, i18n.format("ui.annotations.exported", count, destination));
        }));
    }

    private long exportCsv(AnnotationManagerFilter filter, Path destination) throws Exception {
        return writeAtomically(destination, temp -> {
            long written = 0;
            int exportOffset = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write("id,book_id,book_title,type,color,chapter,quote,note,tags,created_at,updated_at\n");
                while (true) {
                    checkExportCancelled();
                    AnnotationManagerPage page = annotationManagerService.query(filter, exportOffset, EXPORT_PAGE_SIZE);
                    for (AnnotationManagerItem item : page.items()) {
                        checkExportCancelled();
                        writer.write(csv(item.id()) + ',' + csv(item.bookId()) + ',' + csv(item.bookTitle()) + ','
                                + csv(item.type().name()) + ',' + csv(item.color()) + ',' + csv(item.chapterTitle()) + ','
                                + csv(item.quote()) + ',' + csv(item.note()) + ',' + csv(String.join(";", item.tags())) + ','
                                + csv(item.createdAt().toString()) + ',' + csv(item.updatedAt().toString()) + "\n");
                        written++;
                    }
                    if (!page.hasNext()) break;
                    exportOffset += page.limit();
                }
            }
            return written;
        });
    }

    @FXML
    public void exportRich() {
        RichExportValues values = showRichExportDialog();
        if (values == null) return;
        Stage owner = root.getScene() != null && root.getScene().getWindow() instanceof Stage stage ? stage : null;
        String defaultName = "annotations." + values.format().extension();
        java.io.File file = fileChooserService.chooseFileToSave(owner, i18n.text("ui.annotations.export_rich"), defaultName);
        if (file == null) return;
        AnnotationExportSelection selection;
        if (values.scope() == ExportScope.ALL_BOOKS) {
            selection = AnnotationExportSelection.all();
        } else {
            Set<String> ids = values.books().stream().map(BookChoice::id).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (ids.isEmpty()) {
                dialogs.showWarning(i18n.text("common.warning"), i18n.text("ui.annotations.export_select_book"));
                return;
            }
            selection = AnnotationExportSelection.books(ids);
        }
        AnnotationExportRequest request = new AnnotationExportRequest(
                values.format(), selection, file.toPath(), values.templates());
        setBusy(true, i18n.text("ui.annotations.exporting"));
        executor.submit(() -> annotationExportService.export(request)).whenComplete((result, error) -> Platform.runLater(() -> {
            if (disposed) return;
            if (error != null) {
                setBusy(false, i18n.text("ui.annotations.export_failed") + ": " + UiExceptionSupport.message(error));
                dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.export_failed"), UiExceptionSupport.message(error));
                return;
            }
            setBusy(false, i18n.format("ui.annotations.exported", result.annotationCount(), result.destination()));
        }));
    }

    @FXML
    public void exportKnowledgeMarkdown() {
        KnowledgeExportValues values = showKnowledgeExportDialog();
        if (values == null) return;
        Stage owner = root.getScene() != null && root.getScene().getWindow() instanceof Stage stage ? stage : null;
        java.io.File directory = fileChooserService.chooseDirectory(owner, i18n.text("ui.annotations.knowledge_export"));
        if (directory == null) return;
        AnnotationExportSelection selection = exportSelection(values.scope(), values.books());
        if (selection == null) return;

        KnowledgeMarkdownExportRequest request = new KnowledgeMarkdownExportRequest(
                selection, directory.toPath(), values.templates(), values.frontmatter(), values.backlinks(), values.policy());
        setBusy(true, i18n.text("ui.annotations.exporting"));
        executor.submit(() -> knowledgeMarkdownExportService.export(request)).whenComplete((result, error) -> Platform.runLater(() -> {
            if (disposed) return;
            if (error != null) {
                setBusy(false, i18n.text("ui.annotations.export_failed") + ": " + UiExceptionSupport.message(error));
                dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.export_failed"), UiExceptionSupport.message(error));
                return;
            }
            setBusy(false, i18n.format("ui.annotations.knowledge_exported",
                    result.writtenBooks(), result.skippedBooks(), result.annotationCount(), result.rootDirectory()));
        }));
    }

    private AnnotationExportSelection exportSelection(ExportScope scope, List<BookChoice> books) {
        if (scope == ExportScope.ALL_BOOKS) return AnnotationExportSelection.all();
        Set<String> ids = books.stream().map(BookChoice::id).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            dialogs.showWarning(i18n.text("common.warning"), i18n.text("ui.annotations.export_select_book"));
            return null;
        }
        return AnnotationExportSelection.books(ids);
    }

    private KnowledgeExportValues showKnowledgeExportDialog() {
        Dialog<KnowledgeExportValues> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.annotations.knowledge_export"));
        ButtonType export = new ButtonType(i18n.text("ui.annotations.export_action"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(export, ButtonType.CANCEL);

        ComboBox<ScopeChoice> scope = new ComboBox<>(FXCollections.observableArrayList(
                new ScopeChoice(ExportScope.ONE_BOOK, i18n.text("ui.annotations.export_scope_one")),
                new ScopeChoice(ExportScope.SELECTED_BOOKS, i18n.text("ui.annotations.export_scope_selected")),
                new ScopeChoice(ExportScope.ALL_BOOKS, i18n.text("ui.annotations.export_scope_all"))));
        scope.getSelectionModel().selectFirst();

        ListView<BookChoice> books = new ListView<>();
        books.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        books.setPrefHeight(135);
        books.setItems(FXCollections.observableArrayList(bookFilter.getItems().stream()
                .filter(choice -> choice.id() != null).toList()));
        BookChoice active = bookFilter.getValue();
        if (active != null && active.id() != null) books.getSelectionModel().select(active);
        else if (!books.getItems().isEmpty()) books.getSelectionModel().selectFirst();
        scope.valueProperty().addListener((obs, oldValue, newValue) ->
                books.setDisable(newValue != null && newValue.scope() == ExportScope.ALL_BOOKS));

        KnowledgeMarkdownExportTemplates defaults = KnowledgeMarkdownExportTemplates.defaults();
        TextField folderTemplate = new TextField(defaults.folderTemplate());
        TextField fileTemplate = new TextField(defaults.fileNameTemplate());
        TextArea itemTemplate = new TextArea(defaults.annotationTemplate());
        itemTemplate.setPrefRowCount(8);
        CheckBox frontmatter = new CheckBox(i18n.text("ui.annotations.knowledge_frontmatter"));
        frontmatter.setSelected(true);
        CheckBox backlinks = new CheckBox(i18n.text("ui.annotations.knowledge_backlinks"));
        backlinks.setSelected(true);
        ComboBox<PolicyChoice> policy = new ComboBox<>(FXCollections.observableArrayList(
                new PolicyChoice(KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED,
                        i18n.text("ui.annotations.knowledge_policy_replace_managed")),
                new PolicyChoice(KnowledgeMarkdownReExportPolicy.SKIP_EXISTING,
                        i18n.text("ui.annotations.knowledge_policy_skip")),
                new PolicyChoice(KnowledgeMarkdownReExportPolicy.FAIL_IF_EXISTS,
                        i18n.text("ui.annotations.knowledge_policy_fail"))));
        policy.getSelectionModel().selectFirst();

        GridPane grid = new GridPane();
        grid.setHgap(8); grid.setVgap(8);
        grid.addRow(0, new Label(i18n.text("ui.annotations.export_scope")), scope);
        grid.addRow(1, new Label(i18n.text("ui.annotations.export_books")), books);
        grid.addRow(2, new Label(i18n.text("ui.annotations.knowledge_folder_template")), folderTemplate);
        grid.addRow(3, new Label(i18n.text("ui.annotations.knowledge_file_template")), fileTemplate);
        grid.addRow(4, new Label(i18n.text("ui.annotations.export_item_template")), itemTemplate);
        grid.addRow(5, new Label(i18n.text("ui.annotations.knowledge_reexport_policy")), policy);
        grid.add(frontmatter, 1, 6);
        grid.add(backlinks, 1, 7);
        for (Control control : List.of(scope, books, folderTemplate, fileTemplate, itemTemplate, policy)) {
            GridPane.setHgrow(control, javafx.scene.layout.Priority.ALWAYS);
        }
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(820);

        dialog.setResultConverter(button -> {
            if (button != export || scope.getValue() == null || policy.getValue() == null) return null;
            ExportScope selectedScope = scope.getValue().scope();
            List<BookChoice> chosen = selectedScope == ExportScope.ALL_BOOKS
                    ? List.of() : List.copyOf(books.getSelectionModel().getSelectedItems());
            if (selectedScope == ExportScope.ONE_BOOK && chosen.size() > 1) chosen = List.of(chosen.getFirst());
            KnowledgeMarkdownExportTemplates templates = new KnowledgeMarkdownExportTemplates(
                    folderTemplate.getText(), fileTemplate.getText(), itemTemplate.getText()).normalized();
            return new KnowledgeExportValues(selectedScope, chosen, templates,
                    frontmatter.isSelected(), backlinks.isSelected(), policy.getValue().policy());
        });
        return dialog.showAndWait().orElse(null);
    }

    private RichExportValues showRichExportDialog() {
        Dialog<RichExportValues> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.annotations.export_rich"));
        ButtonType export = new ButtonType(i18n.text("ui.annotations.export_action"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(export, ButtonType.CANCEL);

        ComboBox<FormatChoice> format = new ComboBox<>(FXCollections.observableArrayList(
                new FormatChoice(AnnotationExportFormat.MARKDOWN, "Markdown"),
                new FormatChoice(AnnotationExportFormat.JSON, "JSON"),
                new FormatChoice(AnnotationExportFormat.HTML, "HTML")));
        format.getSelectionModel().selectFirst();
        ComboBox<ScopeChoice> scope = new ComboBox<>(FXCollections.observableArrayList(
                new ScopeChoice(ExportScope.ONE_BOOK, i18n.text("ui.annotations.export_scope_one")),
                new ScopeChoice(ExportScope.SELECTED_BOOKS, i18n.text("ui.annotations.export_scope_selected")),
                new ScopeChoice(ExportScope.ALL_BOOKS, i18n.text("ui.annotations.export_scope_all"))));
        scope.getSelectionModel().selectFirst();

        ListView<BookChoice> books = new ListView<>();
        books.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        books.setPrefHeight(150);
        books.setItems(FXCollections.observableArrayList(bookFilter.getItems().stream()
                .filter(choice -> choice.id() != null).toList()));
        BookChoice active = bookFilter.getValue();
        if (active != null && active.id() != null) books.getSelectionModel().select(active);
        else if (!books.getItems().isEmpty()) books.getSelectionModel().selectFirst();

        AnnotationExportTemplates defaults = AnnotationExportTemplates.defaults();
        TextArea documentTemplate = new TextArea(defaults.markdownDocument());
        documentTemplate.setPrefRowCount(5);
        TextArea itemTemplate = new TextArea(defaults.markdownItem());
        itemTemplate.setPrefRowCount(9);
        CheckBox customize = new CheckBox(i18n.text("ui.annotations.export_customize_template"));
        documentTemplate.disableProperty().bind(customize.selectedProperty().not());
        itemTemplate.disableProperty().bind(customize.selectedProperty().not());

        format.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null) return;
            boolean json = newValue.format() == AnnotationExportFormat.JSON;
            customize.setDisable(json);
            if (json) customize.setSelected(false);
            if (newValue.format() == AnnotationExportFormat.HTML) {
                documentTemplate.setText(defaults.htmlDocument());
                itemTemplate.setText(defaults.htmlItem());
            } else if (newValue.format() == AnnotationExportFormat.MARKDOWN) {
                documentTemplate.setText(defaults.markdownDocument());
                itemTemplate.setText(defaults.markdownItem());
            }
        });
        scope.valueProperty().addListener((obs, oldValue, newValue) ->
                books.setDisable(newValue != null && newValue.scope() == ExportScope.ALL_BOOKS));

        GridPane grid = new GridPane();
        grid.setHgap(8); grid.setVgap(8);
        grid.addRow(0, new Label(i18n.text("ui.annotations.export_format")), format);
        grid.addRow(1, new Label(i18n.text("ui.annotations.export_scope")), scope);
        grid.addRow(2, new Label(i18n.text("ui.annotations.export_books")), books);
        grid.add(customize, 1, 3);
        grid.addRow(4, new Label(i18n.text("ui.annotations.export_document_template")), documentTemplate);
        grid.addRow(5, new Label(i18n.text("ui.annotations.export_item_template")), itemTemplate);
        GridPane.setHgrow(format, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(scope, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(books, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(documentTemplate, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(itemTemplate, javafx.scene.layout.Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(760);

        dialog.setResultConverter(button -> {
            if (button != export || format.getValue() == null || scope.getValue() == null) return null;
            AnnotationExportFormat selectedFormat = format.getValue().format();
            AnnotationExportTemplates templates = defaults;
            if (customize.isSelected() && selectedFormat == AnnotationExportFormat.MARKDOWN) {
                templates = new AnnotationExportTemplates(documentTemplate.getText(), itemTemplate.getText(),
                        defaults.htmlDocument(), defaults.htmlItem());
            } else if (customize.isSelected() && selectedFormat == AnnotationExportFormat.HTML) {
                templates = new AnnotationExportTemplates(defaults.markdownDocument(), defaults.markdownItem(),
                        documentTemplate.getText(), itemTemplate.getText());
            }
            ExportScope selectedScope = scope.getValue().scope();
            List<BookChoice> chosen = selectedScope == ExportScope.ALL_BOOKS
                    ? List.of() : List.copyOf(books.getSelectionModel().getSelectedItems());
            if (selectedScope == ExportScope.ONE_BOOK && chosen.size() > 1) chosen = List.of(chosen.getFirst());
            return new RichExportValues(selectedFormat, selectedScope, chosen, templates);
        });
        return dialog.showAndWait().orElse(null);
    }

    private void loadFacetsAndPage() {
        long generation = loadGeneration.incrementAndGet();
        setBusy(true, i18n.text("ui.annotations.loading"));
        executor.submit(annotationManagerService::facets).whenComplete((facets, error) -> Platform.runLater(() -> {
            if (disposed || generation != loadGeneration.get()) return;
            if (error != null) {
                setBusy(false, i18n.text("ui.annotations.load_failed") + ": " + UiExceptionSupport.message(error));
                return;
            }
            applyFacets(facets);
            loadPage(generation);
        }));
    }

    private void loadPage() {
        loadPage(loadGeneration.incrementAndGet());
    }

    private void loadPage(long generation) {
        AnnotationManagerFilter filter = currentFilter();
        if (filter == null) {
            setBusy(false, i18n.text("ui.annotations.invalid_date_range"));
            return;
        }
        setBusy(true, i18n.text("ui.annotations.loading"));
        int requestedOffset = offset;
        executor.submit(() -> annotationManagerService.query(filter, requestedOffset, PAGE_SIZE))
                .whenComplete((page, error) -> Platform.runLater(() -> {
                    if (disposed || generation != loadGeneration.get()) return;
                    if (error != null) {
                        setBusy(false, i18n.text("ui.annotations.load_failed") + ": " + UiExceptionSupport.message(error));
                        return;
                    }
                    currentPage = page;
                    offset = page.offset();
                    table.setItems(FXCollections.observableArrayList(page.items()));
                    table.getSelectionModel().clearSelection();
                    previousButton.setDisable(!page.hasPrevious());
                    nextButton.setDisable(!page.hasNext());
                    long first = page.total() == 0 ? 0 : page.offset() + 1L;
                    long last = page.offset() + page.items().size();
                    pageLabel.setText(i18n.format("ui.annotations.page_status", first, last, page.total()));
                    setBusy(false, i18n.format("ui.annotations.loaded", page.total()));
                    updateActions();
                }));
    }

    private AnnotationManagerFilter currentFilter() {
        try {
            BookChoice book = bookFilter.getValue();
            ValueChoice<AnnotationManagerType> type = typeFilter.getValue();
            ValueChoice<String> color = colorFilter.getValue();
            ValueChoice<String> tag = tagFilter.getValue();
            return new AnnotationManagerFilter(searchField.getText(),
                    book == null ? null : book.id(), type == null ? null : type.value(),
                    color == null ? null : color.value(), tag == null ? null : tag.value(),
                    dateFromFilter.getValue(), dateToFilter.getValue());
        } catch (IllegalArgumentException ex) {
            dialogs.showWarning(i18n.text("common.warning"), i18n.text("ui.annotations.invalid_date_range"));
            return null;
        }
    }

    private void applyFacets(AnnotationManagerFacets facets) {
        String selectedBook = bookFilter.getValue() == null ? null : bookFilter.getValue().id();
        String selectedColor = colorFilter.getValue() == null ? null : colorFilter.getValue().value();
        String selectedTag = tagFilter.getValue() == null ? null : tagFilter.getValue().value();

        List<BookChoice> books = new ArrayList<>();
        books.add(new BookChoice(null, i18n.text("ui.annotations.all_books")));
        for (AnnotationManagerFacets.BookFacet book : facets.books()) books.add(new BookChoice(book.id(), book.title()));
        bookFilter.setItems(FXCollections.observableArrayList(books));
        selectBook(selectedBook);

        List<ValueChoice<String>> colors = new ArrayList<>();
        colors.add(new ValueChoice<>(null, i18n.text("ui.annotations.all_colors")));
        facets.colors().forEach(value -> colors.add(new ValueChoice<>(value, value)));
        colorFilter.setItems(FXCollections.observableArrayList(colors));
        selectValue(colorFilter, selectedColor);

        List<ValueChoice<String>> tags = new ArrayList<>();
        tags.add(new ValueChoice<>(null, i18n.text("ui.annotations.all_tags")));
        facets.tags().forEach(value -> tags.add(new ValueChoice<>(value, value)));
        tagFilter.setItems(FXCollections.observableArrayList(tags));
        selectValue(tagFilter, selectedTag);
    }

    private void selectBook(String id) {
        bookFilter.getItems().stream().filter(choice -> java.util.Objects.equals(choice.id(), id)).findFirst()
                .ifPresentOrElse(bookFilter::setValue, () -> bookFilter.getSelectionModel().selectFirst());
    }

    private static <T> void selectValue(ComboBox<ValueChoice<T>> combo, T value) {
        combo.getItems().stream().filter(choice -> java.util.Objects.equals(choice.value(), value)).findFirst()
                .ifPresentOrElse(combo::setValue, () -> combo.getSelectionModel().selectFirst());
    }

    private Set<String> knownTags() {
        if (tagFilter == null || tagFilter.getItems() == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ValueChoice<String> choice : tagFilter.getItems()) {
            if (choice != null && choice.value() != null && !choice.value().isBlank()) result.add(choice.value());
        }
        return Set.copyOf(result);
    }

    private List<AnnotationManagerItem> selectedItems() {
        return table == null ? List.of() : List.copyOf(table.getSelectionModel().getSelectedItems());
    }

    private void pushUndo(AnnotationBatchUndoToken token) {
        if (token == null) return;
        undoStack.addFirst(token);
        while (undoStack.size() > MAX_UNDO_OPERATIONS) undoStack.removeLast();
    }

    private void finishBatchEdit(Throwable error, Integer count, String successKey) {
        if (disposed) return;
        if (error != null) {
            setBusy(false, i18n.text("ui.annotations.save_failed") + ": " + UiExceptionSupport.message(error));
            dialogs.showError(i18n.text("common.error"), i18n.text("ui.annotations.save_failed"), UiExceptionSupport.message(error));
            return;
        }
        statusLabel.setText(i18n.format(successKey, count == null ? 0 : count));
        loadFacetsAndPage();
    }

    private static String colorHex(javafx.scene.paint.Color c) {
        javafx.scene.paint.Color color = c == null ? javafx.scene.paint.Color.web(AnnotationService.DEFAULT_COLOR) : c;
        int r = (int) Math.round(color.getRed() * 255.0);
        int g = (int) Math.round(color.getGreen() * 255.0);
        int b = (int) Math.round(color.getBlue() * 255.0);
        int a = (int) Math.round(color.getOpacity() * 255.0);
        return a >= 255 ? String.format(java.util.Locale.ROOT, "#%02X%02X%02X", r, g, b)
                : String.format(java.util.Locale.ROOT, "#%02X%02X%02X%02X", r, g, b, a);
    }

    private int exportSelectedRows(List<AnnotationManagerItem> selected, Path destination) throws Exception {
        return writeAtomically(destination, temp -> {
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write("type,book,chapter,quote,note,tags,color,updated");
                writer.newLine();
                for (AnnotationManagerItem item : selected) {
                    checkExportCancelled();
                    writer.write(String.join(",",
                            csv(item.type().name()), csv(item.bookTitle()), csv(item.chapterTitle()),
                            csv(item.quote()), csv(item.note()), csv(String.join(", ", item.tags())),
                            csv(item.color()), csv(item.updatedAt().toString())));
                    writer.newLine();
                }
            }
            return selected.size();
        });
    }

    private long exportSelectedDigest(List<AnnotationManagerItem> selected, Path destination) throws Exception {
        List<AnnotationManagerItem> ordered = selected.stream()
                .sorted(java.util.Comparator.comparing(AnnotationManagerItem::bookTitle, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AnnotationManagerItem::bookId)
                        .thenComparingDouble(AnnotationManagerItem::position)
                        .thenComparing(AnnotationManagerItem::chapterTitle, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AnnotationManagerItem::id))
                .toList();
        return writeAtomically(destination, temp -> {
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write("# " + markdownInline(i18n.text("ui.annotations.digest_title")));
                writer.newLine(); writer.newLine();
                String bookId = null;
                String chapter = null;
                for (AnnotationManagerItem item : ordered) {
                    checkExportCancelled();
                    if (!item.bookId().equals(bookId)) {
                        bookId = item.bookId(); chapter = null;
                        String bookHeading = item.bookTitle().isBlank() ? item.bookId() : item.bookTitle();
                        writer.write("## " + markdownInline(bookHeading)); writer.newLine(); writer.newLine();
                    }
                    String nextChapter = item.chapterTitle().isBlank() ? i18n.text("ui.annotations.digest_no_chapter") : item.chapterTitle();
                    if (!nextChapter.equals(chapter)) {
                        chapter = nextChapter;
                        writer.write("### " + markdownInline(chapter)); writer.newLine(); writer.newLine();
                    }
                    writer.write("- **" + typeLabel(item.type()) + ":** “" + markdownInline(item.quote()) + "”");
                    writer.newLine();
                    if (!item.note().isBlank()) {
                        writer.write("  - **" + i18n.text("ui.reader.annotation.editor.note") + ":** " + markdownInline(item.note()));
                        writer.newLine();
                    }
                    if (!item.tags().isEmpty()) {
                        writer.write("  - **" + i18n.text("ui.reader.annotation.editor.tags") + ":** "
                                + markdownInline(String.join(", ", item.tags())));
                        writer.newLine();
                    }
                    writer.write("  - [" + i18n.text("ui.annotations.open") + "](myhomelib://book/"
                            + java.net.URLEncoder.encode(item.bookId(), StandardCharsets.UTF_8).replace("+", "%20")
                            + "?annotation=" + java.net.URLEncoder.encode(item.id(), StandardCharsets.UTF_8).replace("+", "%20") + ")");
                    writer.newLine(); writer.newLine();
                }
            }
            return (long) ordered.size();
        });
    }

    private <T> T writeAtomically(Path destination, AtomicExportWriter<T> writer) throws Exception {
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path directory = parent != null ? parent : Path.of(".").toAbsolutePath();
        Path temp = Files.createTempFile(directory, "." + absolute.getFileName() + ".", ".part");
        boolean committed = false;
        try {
            T result = writer.write(temp);
            checkExportCancelled();
            AtomicFileSupport.moveReplacing(temp, absolute);
            committed = true;
            return result;
        } finally {
            if (!committed) Files.deleteIfExists(temp);
        }
    }

    private static void checkExportCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("annotation export cancelled");
    }

    private static javafx.scene.paint.Color parseColor(String value) {
        try {
            return javafx.scene.paint.Color.web(value == null || value.isBlank() ? AnnotationService.DEFAULT_COLOR : value);
        } catch (RuntimeException ignored) {
            return javafx.scene.paint.Color.web(AnnotationService.DEFAULT_COLOR);
        }
    }

    @FunctionalInterface
    private interface AtomicExportWriter<T> {
        T write(Path temp) throws Exception;
    }

    private static String markdownInline(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("`", "\\`")
                .replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("\r\n", " ").replace("\r", " ").replace("\n", " ");
    }

    private void updateActions() {
        int selectedCount = table == null ? 0 : table.getSelectionModel().getSelectedItems().size();
        boolean busy = progressIndicator != null && progressIndicator.isVisible();
        openButton.setDisable(selectedCount != 1 || busy);
        editButton.setDisable(selectedCount != 1 || busy);
        deleteButton.setDisable(selectedCount == 0 || busy);
        undoButton.setDisable(undoStack.isEmpty() || busy);
        if (undoButton != null) {
            int undoCount = undoStack.isEmpty() ? 0 : undoStack.peekFirst().annotations().size();
            undoButton.setText(undoCount <= 0 ? i18n.text("ui.annotations.undo")
                    : i18n.format("ui.annotations.undo_count", undoCount));
        }
        if (batchColorButton != null) batchColorButton.setDisable(selectedCount == 0 || busy);
        if (batchTagsButton != null) batchTagsButton.setDisable(selectedCount == 0 || busy);
        if (batchRemoveTagsButton != null) batchRemoveTagsButton.setDisable(selectedCount == 0 || busy);
        if (exportSelectedButton != null) exportSelectedButton.setDisable(selectedCount == 0 || busy);
        if (selectedLabel != null) {
            selectedLabel.setText(selectedCount == 0 ? "" : i18n.format("ui.annotations.selected_count", selectedCount));
            selectedLabel.setManaged(selectedCount > 0);
            selectedLabel.setVisible(selectedCount > 0);
        }
    }

    private void setBusy(boolean busy, String status) {
        progressIndicator.setVisible(busy);
        progressIndicator.setManaged(busy);
        if (status != null) statusLabel.setText(status);
        updateActions();
    }

    private String typeLabel(AnnotationManagerType type) {
        return type == AnnotationManagerType.NOTE
                ? i18n.text("ui.annotations.type.note") : i18n.text("ui.annotations.type.highlight");
    }

    private static String summaryText(AnnotationManagerItem item) {
        String text = item.note().isBlank() ? item.quote() : item.note();
        text = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return text.length() <= 180 ? text : text.substring(0, 177) + "…";
    }

    static String csv(String value) {
        String safe = value == null ? "" : value;
        // Spreadsheet applications may execute cells beginning with formula markers even when the
        // CSV field is quoted. Prefix only the exported representation; stored annotation data is
        // left untouched. Leading whitespace is ignored when deciding whether the cell is risky.
        int first = 0;
        while (first < safe.length() && Character.isWhitespace(safe.charAt(first))) first++;
        if (first < safe.length() && "=+-@".indexOf(safe.charAt(first)) >= 0) {
            safe = safe.substring(0, first) + "'" + safe.substring(first);
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }


    @Override
    public void dispose() {
        disposed = true;
        loadGeneration.incrementAndGet();
        table.setItems(FXCollections.observableArrayList());
        undoStack.clear();
    }

    private enum ExportScope { ONE_BOOK, SELECTED_BOOKS, ALL_BOOKS }
    private record ScopeChoice(ExportScope scope, String label) {
        @Override public String toString() { return label == null ? "" : label; }
    }
    private record FormatChoice(AnnotationExportFormat format, String label) {
        @Override public String toString() { return label; }
    }
    private record RichExportValues(AnnotationExportFormat format, ExportScope scope,
                                    List<BookChoice> books, AnnotationExportTemplates templates) { }
    private record PolicyChoice(KnowledgeMarkdownReExportPolicy policy, String label) {
        @Override public String toString() { return label == null ? "" : label; }
    }
    private record KnowledgeExportValues(ExportScope scope, List<BookChoice> books,
                                         KnowledgeMarkdownExportTemplates templates, boolean frontmatter,
                                         boolean backlinks, KnowledgeMarkdownReExportPolicy policy) { }
    private record BookChoice(String id, String label) {
        @Override public String toString() { return label == null || label.isBlank() ? id == null ? "" : id : label; }
    }
    private record ValueChoice<T>(T value, String label) {
        @Override public String toString() { return label == null ? "" : label; }
    }
}
