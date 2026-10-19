package com.myhomelibcorp.ui.organizer;

import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.organizer.analysis.AuthorFolderPreviewPort;
import com.myhomelibcorp.organizer.analysis.AuthorFolderOrderPreview;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only author-folder preview and explicitly requested offline export actions.
 * This owns no organizer Apply/Undo state or SQLite transactions.
 */
@Slf4j
final class OrganizerAuthorFolderActions {
    private final ApplicationState appState;
    private final AuthorFolderPreviewPort authorFolderPreview;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final FileChooserService fileChooserService;
    private final Stage stage;
    private final RadioButton currentCollectionSource;
    private final Button authorPreviewButton;
    private final Button authorBatchButton;
    private final Button authorSelectionButton;

    OrganizerAuthorFolderActions(ApplicationState appState, AuthorFolderPreviewPort authorFolderPreview,
                                 UiBackgroundExecutor background, DialogService dialogs,
                                 FileChooserService fileChooserService, Stage stage,
                                 RadioButton currentCollectionSource, Button authorPreviewButton,
                                 Button authorBatchButton, Button authorSelectionButton) {
        this.appState = appState;
        this.authorFolderPreview = authorFolderPreview;
        this.background = background;
        this.dialogs = dialogs;
        this.fileChooserService = fileChooserService;
        this.stage = stage;
        this.currentCollectionSource = currentCollectionSource;
        this.authorPreviewButton = authorPreviewButton;
        this.authorBatchButton = authorBatchButton;
        this.authorSelectionButton = authorSelectionButton;
    }

    void previewAuthorFolder() {
        var collection = appState.getCurrentLibraryCollection();
        if (collection == null || collection.getRootFolder() == null || collection.getDbFile() == null
                || !currentCollectionSource.isSelected()) {
            dialogs.showWarning(UiText.text("ui.organizer.author_preview.title"),
                    UiText.text("ui.organizer.author_preview.requires_collection"));
            return;
        }
        var name = dialogs.showTextInput(UiText.text("ui.organizer.author_preview.title"),
                UiText.text("ui.organizer.author_preview.explanation"),
                UiText.text("ui.organizer.author_preview.input"), "");
        if (name.isEmpty() || name.get().isBlank()) return;
        String expectedCollection = collection.getId();
        Path root = collection.getRootFolder().toAbsolutePath().normalize();
        Path database;
        try {
            database = Path.of(collection.getDbFile()).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            dialogs.showError(UiText.text("ui.organizer.author_preview.title"),
                    UiText.text("common.error.invalid_data"));
            return;
        }
        authorPreviewButton.setDisable(true);
        authorPreviewButton.setText(UiText.text("ui.organizer.author_preview.started"));
        background.submitCollectionScopedAwait(
                com.myhomelibcorp.application.operation.LibraryOperationType.QUERY,
                () -> authorFolderPreview.inspect(database, root, name.get().trim()))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    authorPreviewButton.setDisable(false);
                    authorPreviewButton.setText(UiText.text("ui.organizer.author_preview.button"));
                    if (!stage.isShowing() || !Objects.equals(expectedCollection, appState.getCurrentLibraryCollectionId())) {
                        return;
                    }
                    if (error != null) {
                        log.warn("AUTHOR_FOLDER_PREVIEW_FAILED collection={} folder={}", expectedCollection, name.get(), error);
                        dialogs.showError(UiText.text("ui.organizer.author_preview.title"),
                                UiText.text("ui.organizer.author_preview.unavailable"));
                        return;
                    }
                    String outcome = UiText.text("ui.organizer.author_preview.status."
                            + result.status().name().toLowerCase(Locale.ROOT));
                    String details = UiText.format("ui.organizer.author_preview.result",
                            result.currentName(),
                            result.proposedName().isEmpty() ? UiText.text("ui.organizer.author_preview.unknown")
                                    : result.proposedName(), result.matchingBooks(), outcome);
                    dialogs.showInfo(UiText.text("ui.organizer.author_preview.title"),
                            details + "\n\n" + UiText.text("ui.organizer.author_preview.disclaimer"));
                }));
    }

    /** One read-only SQLite scan, one filesystem listing, then an atomic CSV report. */
    void auditAuthorFolders() {
        var collection = appState.getCurrentLibraryCollection();
        if (collection == null || collection.getRootFolder() == null || collection.getDbFile() == null
                || !currentCollectionSource.isSelected()) {
            dialogs.showWarning(UiText.text("ui.organizer.author_batch.title"),
                    UiText.text("ui.organizer.author_preview.requires_collection"));
            return;
        }
        var output = fileChooserService.chooseFileToSave(stage,
                UiText.text("ui.organizer.author_batch.title"), "author-folders-audit.csv",
                List.of(new javafx.stage.FileChooser.ExtensionFilter(
                        UiText.text("ui.organizer.author_batch.csv"), "*.csv")));
        if (output == null) return;
        String expectedCollection = collection.getId();
        Path root = collection.getRootFolder().toAbsolutePath().normalize();
        Path database;
        try {
            database = Path.of(collection.getDbFile()).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            dialogs.showError(UiText.text("ui.organizer.author_batch.title"),
                    UiText.text("common.error.invalid_data"));
            return;
        }
        Path target = output.toPath().toAbsolutePath().normalize();
        if (!AuthorFolderExportLocation.safelyOutsideLibrary(target, root, database)) {
            dialogs.showWarning(UiText.text("ui.organizer.author_batch.title"),
                    UiText.text("ui.organizer.author_batch.outside_library"));
            return;
        }
        var translations = new EnumMap<AuthorFolderOrderPreview.Status, String>(AuthorFolderOrderPreview.Status.class);
        for (var status : AuthorFolderOrderPreview.Status.values()) {
            translations.put(status, UiText.text("ui.organizer.author_preview.status."
                    + status.name().toLowerCase(Locale.ROOT)));
        }
        List<String> columns = List.of(
                UiText.text("ui.organizer.author_batch.column.current"),
                UiText.text("ui.organizer.author_batch.column.proposed"),
                UiText.text("ui.organizer.author_batch.column.books"),
                UiText.text("ui.organizer.author_batch.column.status"));
        authorBatchButton.setDisable(true);
        authorBatchButton.setText(UiText.text("ui.organizer.author_batch.started"));
        background.submitCollectionScopedAwait(
                com.myhomelibcorp.application.operation.LibraryOperationType.QUERY,
                () -> {
                    var rows = authorFolderPreview.inspectAll(database, root);
                    if (!AuthorFolderExportLocation.safelyOutsideLibrary(target, root, database)) {
                        throw new java.io.IOException("Organizer report destination is unsafe");
                    }
                    AtomicFileSupport.writeStringReplacing(target,
                            AuthorFolderBatchCsv.render(rows, columns, translations), StandardCharsets.UTF_8);
                    return rows;
                }).whenComplete((rows, error) -> UiExecutor.runOnUiThread(() -> {
                    authorBatchButton.setDisable(false);
                    authorBatchButton.setText(UiText.text("ui.organizer.author_batch.button"));
                    if (!stage.isShowing()
                            || !Objects.equals(expectedCollection, appState.getCurrentLibraryCollectionId())) return;
                    if (error != null) {
                        log.warn("AUTHOR_FOLDER_BATCH_AUDIT_FAILED collection={}", expectedCollection, error);
                        dialogs.showError(UiText.text("ui.organizer.author_batch.title"),
                                UiText.text("ui.organizer.author_batch.failed"));
                        return;
                    }
                    long proposals = rows.stream().filter(row -> row.status() == AuthorFolderOrderPreview.Status.PROPOSAL).count();
                    long conflicts = rows.stream().filter(row -> row.status() == AuthorFolderOrderPreview.Status.TARGET_EXISTS).count();
                    long ambiguous = rows.stream().filter(row -> row.status() == AuthorFolderOrderPreview.Status.AMBIGUOUS).count();
                    dialogs.showInfo(UiText.text("ui.organizer.author_batch.title"),
                            UiText.format("ui.organizer.author_batch.finished",
                                    rows.size(), proposals, conflicts, ambiguous)
                            + "\n\n" + UiText.text("ui.organizer.author_preview.disclaimer"));
                }));
    }

    /**
     * Export an explicitly chosen, limited list of *source* folder names.
     * No Python process is launched and no SQLite or library files are modified.
     * Only a closed-app offline tool may later inspect this list and ask for
     * separate cryptographic approval after rechecking disk and catalog.
     */
    void prepareOfflineAuthorRepair() {
        var collection = appState.getCurrentLibraryCollection();
        if (collection == null || collection.getRootFolder() == null || collection.getDbFile() == null
                || !currentCollectionSource.isSelected()) {
            dialogs.showWarning(UiText.text("ui.organizer.author_selection.title"),
                    UiText.text("ui.organizer.author_preview.requires_collection"));
            return;
        }
        Path root = collection.getRootFolder().toAbsolutePath().normalize();
        Path database;
        try {
            database = Path.of(collection.getDbFile()).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            dialogs.showError(UiText.text("ui.organizer.author_selection.title"),
                    UiText.text("common.error.invalid_data"));
            return;
        }
        String expectedCollection = collection.getId();
        authorSelectionButton.setDisable(true);
        authorSelectionButton.setText(UiText.text("ui.organizer.author_selection.started"));
        background.submitCollectionScopedAwait(
                com.myhomelibcorp.application.operation.LibraryOperationType.QUERY,
                () -> authorFolderPreview.inspectAll(database, root))
                .whenComplete((results, error) -> UiExecutor.runOnUiThread(() -> {
                    authorSelectionButton.setDisable(false);
                    authorSelectionButton.setText(UiText.text("ui.organizer.author_selection.button"));
                    if (!stage.isShowing()
                            || !Objects.equals(expectedCollection, appState.getCurrentLibraryCollectionId())) return;
                    if (error != null) {
                        log.warn("AUTHOR_FOLDER_SELECTION_FAILED collection={}", expectedCollection, error);
                        dialogs.showError(UiText.text("ui.organizer.author_selection.title"),
                                UiText.text("ui.organizer.author_selection.failed"));
                        return;
                    }
                    List<AuthorFolderOrderPreview.Result> candidates = results.stream()
                            .filter(item -> item.status() == AuthorFolderOrderPreview.Status.PROPOSAL
                                    && item.matchingBooks() > 0).toList();
                    if (candidates.isEmpty()) {
                        dialogs.showInfo(UiText.text("ui.organizer.author_selection.title"),
                                UiText.text("ui.organizer.author_selection.empty"));
                        return;
                    }
                    selectOfflineAuthorRepairFolders(candidates, root, database, expectedCollection);
                }));
    }

    private void selectOfflineAuthorRepairFolders(List<AuthorFolderOrderPreview.Result> candidates,
                                                   Path root, Path database, String expectedCollection) {
        var choices = new ListView<AuthorFolderOrderPreview.Result>();
        choices.getItems().setAll(candidates);
        choices.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        choices.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(AuthorFolderOrderPreview.Result item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.currentName() + " → " + item.proposedName());
            }
        });
        choices.setPrefSize(560, 340);
        var instruction = wrap(UiText.text("ui.organizer.author_selection.explanation"));
        var body = new VBox(10, instruction, choices);
        body.setPadding(new Insets(12));
        var dialog = new Dialog<ButtonType>();
        DialogLocalization.prepare(dialog);
        dialog.initOwner(stage);
        dialog.setTitle(UiText.text("ui.organizer.author_selection.title"));
        dialog.getDialogPane().setContent(body);
        ButtonType save = new ButtonType(UiText.text("common.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(UiText.text("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, cancel);
        if (dialog.showAndWait().orElse(cancel) != save) return;
        if (!Objects.equals(expectedCollection, appState.getCurrentLibraryCollectionId())) return;
        List<AuthorFolderOrderPreview.Result> selection = List.copyOf(
                choices.getSelectionModel().getSelectedItems());
        String contents;
        try {
            contents = AuthorFolderRepairSelection.render(selection);
        } catch (IllegalArgumentException invalidSelection) {
            dialogs.showWarning(UiText.text("ui.organizer.author_selection.title"),
                    UiText.text("ui.organizer.author_selection.invalid"));
            return;
        }
        var output = fileChooserService.chooseFileToSave(stage,
                UiText.text("ui.organizer.author_selection.title"), "author-folders-selected.txt",
                List.of(new javafx.stage.FileChooser.ExtensionFilter(
                        UiText.text("ui.organizer.author_selection.text_file"), "*.txt")));
        if (output == null) return;
        Path target = output.toPath().toAbsolutePath().normalize();
        if (!AuthorFolderExportLocation.safelyOutsideLibrary(target, root, database)) {
            dialogs.showWarning(UiText.text("ui.organizer.author_selection.title"),
                    UiText.text("ui.organizer.author_batch.outside_library"));
            return;
        }
        // The selection file is tiny, but its I/O still stays off JavaFX.
        authorSelectionButton.setDisable(true);
        background.submitCollectionScopedAwait(
                com.myhomelibcorp.application.operation.LibraryOperationType.QUERY,
                () -> {
                    if (!AuthorFolderExportLocation.safelyOutsideLibrary(target, root, database)) {
                        throw new java.io.IOException("Organizer selection destination is unsafe");
                    }
                    AtomicFileSupport.writeStringReplacing(target, contents, StandardCharsets.UTF_8);
                    return target;
                }).whenComplete((saved, error) -> UiExecutor.runOnUiThread(() -> {
                    authorSelectionButton.setDisable(false);
                    if (!stage.isShowing()
                            || !Objects.equals(expectedCollection, appState.getCurrentLibraryCollectionId())) return;
                    if (error != null) {
                        log.warn("AUTHOR_FOLDER_SELECTION_SAVE_FAILED", error);
                        dialogs.showError(UiText.text("ui.organizer.author_selection.title"),
                                UiText.text("ui.organizer.author_selection.failed"));
                    } else {
                        dialogs.showInfo(UiText.text("ui.organizer.author_selection.title"),
                                UiText.format("ui.organizer.author_selection.saved", selection.size()));
                    }
                }));
    }

    private static Label wrap(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }
}
