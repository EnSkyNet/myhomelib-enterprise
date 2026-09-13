package com.myhomelibcorp.ui.metadata;

import com.myhomelibcorp.application.bulkedit.BatchMetadataEditAction;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditField;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditPreview;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditProgress;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditRule;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditUseCase;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditableSnapshot;
import com.myhomelibcorp.application.history.LibraryOperationHistoryEntry;
import com.myhomelibcorp.application.history.LibraryOperationHistoryUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Reachable JavaFX workflow for MHL-111 local batch editing and MHL-112 shared undo. */
@Service
public class BulkMetadataEditUiService {
    private static final EnumSet<BatchMetadataEditField> TEXT_FIELDS = EnumSet.of(
            BatchMetadataEditField.TITLE,
            BatchMetadataEditField.SERIES,
            BatchMetadataEditField.PUBLISHER,
            BatchMetadataEditField.TAGS,
            BatchMetadataEditField.ANNOTATION);

    private final BatchMetadataEditUseCase batchEdit;
    private final LibraryOperationHistoryUseCase history;
    private final BookSelectionService selection;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final LocalizationService i18n;
    private final ApplicationState state;
    private final AtomicLong generation = new AtomicLong();

    public BulkMetadataEditUiService(BatchMetadataEditUseCase batchEdit,
                                     LibraryOperationHistoryUseCase history,
                                     BookSelectionService selection,
                                     UiBackgroundExecutor background,
                                     DialogService dialogs,
                                     LocalizationService i18n,
                                     ApplicationState state) {
        this.batchEdit = batchEdit;
        this.history = history;
        this.selection = selection;
        this.background = background;
        this.dialogs = dialogs;
        this.i18n = i18n;
        this.state = state;
    }

    public void editChecked(Window owner, Runnable onApplied) {
        List<BookId> ids = selection.snapshot();
        if (ids.isEmpty()) {
            dialogs.showWarning(i18n.text("ui.bulkedit.title"), i18n.text("ui.bulkedit.select_books"));
            return;
        }
        if (ids.size() > BatchMetadataEditUseCase.MAX_SELECTED_BOOKS) {
            dialogs.showWarning(i18n.text("ui.bulkedit.title"),
                    i18n.format("ui.bulkedit.limit", BatchMetadataEditUseCase.MAX_SELECTED_BOOKS));
            return;
        }
        Optional<List<BatchMetadataEditRule>> requested = editRules(owner);
        if (requested.isEmpty()) return;

        var token = UiAsyncRequestGuard.next(generation, state);
        AtomicBoolean previewFinished = new AtomicBoolean();
        AtomicBoolean previewCancelled = new AtomicBoolean();
        Dialog<ButtonType> preparing = showProgress(owner, i18n.text("ui.bulkedit.preview_preparing"),
                new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS), previewFinished, previewCancelled);
        background.submit(() -> {
            if (previewCancelled.get()) throw new CancellationException("Preview cancelled");
            return batchEdit.preview(token.collectionId(), ids, requested.get());
        }).whenComplete((preview, error) -> Platform.runLater(() -> {
            previewFinished.set(true);
            preparing.close();
            if (previewCancelled.get() || !UiAsyncRequestGuard.isCurrent(token, generation, state)) return;
            if (error != null) {
                if (!isCancellation(error)) dialogs.showError(i18n.text("ui.bulkedit.title"), UiExceptionMessages.root(error));
                return;
            }
            if (!confirmPreview(owner, preview)) return;
            if (!UiAsyncRequestGuard.isCurrent(token, generation, state)) return;
            execute(owner, token, ids, requested.get(), onApplied);
        }));
    }

    public void undoLatest(Window owner, Runnable onApplied) {
        var token = UiAsyncRequestGuard.next(generation, state);
        background.submit(history::latestUndoable).whenComplete((latest, error) -> Platform.runLater(() -> {
            if (!UiAsyncRequestGuard.isCurrent(token, generation, state)) return;
            if (error != null) {
                dialogs.showError(i18n.text("ui.history.title"), UiExceptionMessages.root(error));
                return;
            }
            if (latest == null || latest.isEmpty()) {
                dialogs.showInfo(i18n.text("ui.history.title"), i18n.text("ui.history.nothing_to_undo"));
                return;
            }
            LibraryOperationHistoryEntry entry = latest.get();
            String details = i18n.format("ui.history.undo_confirm", historyType(entry), entry.changedCount(), entry.summary());
            if (!dialogs.showConfirmation(i18n.text("ui.history.title"), i18n.text("ui.history.undo_header"), details)) return;
            executeUndo(owner, token, entry.operationId(), onApplied);
        }));
    }

    private void execute(Window owner, com.myhomelibcorp.ui.util.UiAsyncRequestToken token,
                         List<BookId> ids, List<BatchMetadataEditRule> rules, Runnable onApplied) {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        ProgressBar bar = new ProgressBar(0);
        Label label = new Label(i18n.format("ui.bulkedit.progress", 0, ids.size()));
        Dialog<ButtonType> progressDialog = showProgress(owner, label, bar, finished, cancelled);
        background.submit(() -> batchEdit.execute(token.collectionId(), ids, rules, cancelled,
                update -> Platform.runLater(() -> updateProgress(finished, label, bar, update))))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    finished.set(true);
                    progressDialog.close();
                    if (!UiAsyncRequestGuard.isCurrent(token, generation, state)) return;
                    if (error != null) {
                        if (!isCancellation(error)) dialogs.showError(i18n.text("ui.bulkedit.title"), UiExceptionMessages.root(error));
                        return;
                    }
                    if (onApplied != null) onApplied.run();
                    dialogs.showInfo(i18n.text("ui.bulkedit.title"),
                            i18n.format("ui.bulkedit.completed", result.changedCount(), result.selectedCount()));
                }));
    }

    private void executeUndo(Window owner, com.myhomelibcorp.ui.util.UiAsyncRequestToken token,
                             String expectedOperationId, Runnable onApplied) {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        Label label = new Label(i18n.text("ui.history.undo_progress"));
        Dialog<ButtonType> progressDialog = showProgress(owner, label, bar, finished, cancelled);
        background.submit(() -> history.undo(token.collectionId(), expectedOperationId, cancelled, update -> Platform.runLater(() -> {
            if (finished.get() || update.total() <= 0) return;
            bar.setProgress((double) update.processed() / update.total());
            label.setText(i18n.format("ui.history.undo_progress_count", update.processed(), update.total()));
        }))).whenComplete((result, error) -> Platform.runLater(() -> {
            finished.set(true);
            progressDialog.close();
            if (!UiAsyncRequestGuard.isCurrent(token, generation, state)) return;
            if (error != null) {
                if (!isCancellation(error)) dialogs.showError(i18n.text("ui.history.title"), UiExceptionMessages.root(error));
                return;
            }
            if (onApplied != null) onApplied.run();
            dialogs.showInfo(i18n.text("ui.history.title"),
                    i18n.format("ui.history.undo_completed", result.affectedCount()));
        }));
    }

    private Optional<List<BatchMetadataEditRule>> editRules(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.bulkedit.title"));
        dialog.setHeaderText(i18n.text("ui.bulkedit.rules_header"));
        if (owner != null) dialog.initOwner(owner);
        ButtonType apply = new ButtonType(i18n.text("common.apply"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        ComboBox<BatchMetadataEditField> field = new ComboBox<>(FXCollections.observableArrayList(BatchMetadataEditField.values()));
        field.getSelectionModel().selectFirst();
        ComboBox<BatchMetadataEditAction> action = new ComboBox<>();
        TextField value = new TextField();
        TextField pattern = new TextField();
        TextField replacement = new TextField();
        value.setPromptText(i18n.text("ui.bulkedit.value_hint"));
        pattern.setPromptText(i18n.text("ui.bulkedit.pattern_hint"));
        replacement.setPromptText(i18n.text("ui.bulkedit.replacement_hint"));

        ListView<BatchMetadataEditRule> rules = new ListView<>();
        rules.setPrefHeight(180);
        rules.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(BatchMetadataEditRule item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : ruleLabel(item));
            }
        });
        Button add = new Button(i18n.text("ui.bulkedit.add_rule"));
        Button remove = new Button(i18n.text("ui.bulkedit.remove_rule"));
        remove.disableProperty().bind(rules.getSelectionModel().selectedItemProperty().isNull());
        add.setOnAction(event -> rules.getItems().add(new BatchMetadataEditRule(
                field.getValue(), action.getValue(), value.getText(), pattern.getText(), replacement.getText())));
        remove.setOnAction(event -> {
            int index = rules.getSelectionModel().getSelectedIndex();
            if (index >= 0) rules.getItems().remove(index);
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label(i18n.text("ui.bulkedit.field")), field);
        form.addRow(1, new Label(i18n.text("ui.bulkedit.action")), action);
        Label valueLabel = new Label(i18n.text("ui.bulkedit.value"));
        Label patternLabel = new Label(i18n.text("ui.bulkedit.pattern"));
        Label replacementLabel = new Label(i18n.text("ui.bulkedit.replacement"));
        form.addRow(2, valueLabel, value);
        form.addRow(3, patternLabel, pattern);
        form.addRow(4, replacementLabel, replacement);
        form.add(add, 1, 5);
        GridPane.setHgrow(field, Priority.ALWAYS);
        GridPane.setHgrow(action, Priority.ALWAYS);
        GridPane.setHgrow(value, Priority.ALWAYS);
        GridPane.setHgrow(pattern, Priority.ALWAYS);
        GridPane.setHgrow(replacement, Priority.ALWAYS);

        Runnable refreshActions = () -> {
            BatchMetadataEditField selected = field.getValue();
            List<BatchMetadataEditAction> available = TEXT_FIELDS.contains(selected)
                    ? List.of(BatchMetadataEditAction.values())
                    : List.of(BatchMetadataEditAction.SET, BatchMetadataEditAction.CLEAR);
            BatchMetadataEditAction previous = action.getValue();
            action.setItems(FXCollections.observableArrayList(available));
            if (previous != null && available.contains(previous)) action.setValue(previous); else action.getSelectionModel().selectFirst();
        };
        Runnable refreshInputs = () -> {
            BatchMetadataEditAction selected = action.getValue();
            boolean usesValue = selected == BatchMetadataEditAction.SET;
            boolean usesRegex = selected == BatchMetadataEditAction.REGEX_REPLACE;
            setManagedVisible(valueLabel, usesValue); setManagedVisible(value, usesValue);
            setManagedVisible(patternLabel, usesRegex); setManagedVisible(pattern, usesRegex);
            setManagedVisible(replacementLabel, usesRegex); setManagedVisible(replacement, usesRegex);
        };
        field.valueProperty().addListener((obs, oldValue, newValue) -> { refreshActions.run(); refreshInputs.run(); });
        action.valueProperty().addListener((obs, oldValue, newValue) -> refreshInputs.run());
        refreshActions.run();
        refreshInputs.run();

        VBox content = new VBox(10, form, new Label(i18n.text("ui.bulkedit.rules")), rules, remove);
        content.setPadding(new Insets(10));
        content.setPrefWidth(680);
        dialog.getDialogPane().setContent(content);
        Node applyButton = dialog.getDialogPane().lookupButton(apply);
        applyButton.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(rules.getItems()));
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != apply) return Optional.empty();
        return Optional.of(List.copyOf(rules.getItems()));
    }

    private boolean confirmPreview(Window owner, BatchMetadataEditPreview preview) {
        StringBuilder text = new StringBuilder();
        text.append(i18n.format("ui.bulkedit.preview_count", preview.selectedCount(), preview.sample().size())).append("\n\n");
        int changedSample = 0;
        for (var item : preview.sample()) {
            if (item.changedFields().isEmpty()) continue;
            changedSample++;
            text.append(item.bookId()).append("\n");
            for (BatchMetadataEditField field : item.changedFields()) {
                text.append("  ").append(fieldLabel(field)).append(": ")
                        .append(value(item.before(), field)).append(" → ")
                        .append(value(item.after(), field)).append("\n");
            }
        }
        if (changedSample == 0) text.append(i18n.text("ui.bulkedit.preview_no_sample_changes"));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.bulkedit.title"));
        dialog.setHeaderText(i18n.text("ui.bulkedit.preview_header"));
        if (owner != null) dialog.initOwner(owner);
        ButtonType apply = new ButtonType(i18n.text("common.apply"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        TextArea area = new TextArea(text.toString());
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefColumnCount(90);
        area.setPrefRowCount(Math.min(24, Math.max(8, 4 + preview.sample().size())));
        dialog.getDialogPane().setContent(area);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == apply;
    }

    private Dialog<ButtonType> showProgress(Window owner, String label, ProgressBar bar,
                                            AtomicBoolean finished, AtomicBoolean cancelled) {
        return showProgress(owner, new Label(label), bar, finished, cancelled);
    }

    private Dialog<ButtonType> showProgress(Window owner, Label label, ProgressBar bar,
                                            AtomicBoolean finished, AtomicBoolean cancelled) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.bulkedit.title"));
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        bar.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(10, label, bar);
        content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);
        dialog.setOnCloseRequest(event -> { if (!finished.get()) cancelled.set(true); });
        dialog.show();
        return dialog;
    }

    private static void updateProgress(AtomicBoolean finished, Label label, ProgressBar bar, BatchMetadataEditProgress update) {
        if (finished.get() || update == null || update.total() <= 0) return;
        bar.setProgress((double) update.processed() / update.total());
        label.setText(update.processed() + " / " + update.total());
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void setManagedVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private String ruleLabel(BatchMetadataEditRule rule) {
        String suffix = switch (rule.action()) {
            case SET -> " = " + safe(rule.value());
            case CLEAR -> "";
            case REGEX_REPLACE -> " /" + safe(rule.pattern()) + "/ → " + safe(rule.replacement());
            case TRIM, CAPITALIZE -> "";
        };
        return fieldLabel(rule.field()) + " · " + actionLabel(rule.action()) + suffix;
    }

    private String fieldLabel(BatchMetadataEditField field) {
        return i18n.text("ui.bulkedit.field." + field.name().toLowerCase(Locale.ROOT));
    }

    private String actionLabel(BatchMetadataEditAction action) {
        return i18n.text("ui.bulkedit.action." + action.name().toLowerCase(Locale.ROOT));
    }

    private String historyType(LibraryOperationHistoryEntry entry) {
        return i18n.text("ui.history.type." + entry.type().name().toLowerCase(Locale.ROOT));
    }

    private String value(BatchMetadataEditableSnapshot snapshot, BatchMetadataEditField field) {
        Object result = switch (field) {
            case TITLE -> snapshot.title();
            case SERIES -> snapshot.series();
            case PUBLISHER -> snapshot.publisher();
            case TAGS -> snapshot.tags();
            case ANNOTATION -> snapshot.annotation();
            case LANGUAGE -> snapshot.language();
            case YEAR -> snapshot.year();
            case GENRES -> snapshot.genres().stream().map(g -> g.code()).toList();
        };
        return result == null || result.toString().isBlank() ? "—" : result.toString();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
