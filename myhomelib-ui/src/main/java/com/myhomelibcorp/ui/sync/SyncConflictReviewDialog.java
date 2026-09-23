package com.myhomelibcorp.ui.sync;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewItem;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit review UI for conflicts that cannot be resolved without risking user-data loss. */
public final class SyncConflictReviewDialog {
    private final LocalizationService i18n;

    public SyncConflictReviewDialog(LocalizationService i18n) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
    }

    public Optional<List<SyncConflictReviewSelection>> review(Window owner,
                                                               List<SyncConflictReviewItem> conflicts) {
        SyncConflictReviewModel model = new SyncConflictReviewModel(conflicts);
        if (model.rows().isEmpty()) return Optional.of(List.of());

        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(i18n.text("ui.text.synchronization_conflicts"));
        dialog.setHeaderText(i18n.text("ui.text.review_conflicts_that_cannot_be_merged_automatic_1471c3b"));
        ButtonType apply = new ButtonType(i18n.text("ui.text.apply_selection"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(i18n.text("ui.text.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(apply, cancel);

        List<RowEditor> editors = new ArrayList<>();
        VBox content = new VBox(12);
        content.setPadding(new Insets(8));
        for (SyncConflictReviewModel.Row row : model.rows()) {
            RowEditor editor = new RowEditor(row, i18n);
            editors.add(editor);
            content.getChildren().add(editor.node);
        }
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(760);
        scroll.setPrefViewportHeight(Math.min(620, 210 + model.rows().size() * 120));
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefWidth(820);

        if (dialog.showAndWait().filter(apply::equals).isEmpty()) return Optional.empty();
        return Optional.of(editors.stream().map(RowEditor::result).map(SyncConflictReviewModel.Row::selection).toList());
    }

    private static final class RowEditor {
        private final SyncConflictReviewModel.Row row;
        private final ComboBox<SyncConflictReviewSelection.Side> choice = new ComboBox<>();
        private final GridPane node = new GridPane();

        private RowEditor(SyncConflictReviewModel.Row row, LocalizationService i18n) {
            this.row = row;
            node.setHgap(10);
            node.setVgap(6);
            node.setPadding(new Insets(8));
            node.getStyleClass().add("sync-conflict-review-row");

            Label entity = new Label(render(i18n, row.item().entityType()));
            entity.setAccessibleText(i18n.format("ui.sync.conflict.accessible_entity", row.item().logicalKey()));
            Label reason = new Label(render(i18n, row.item().reason()));
            reason.setWrapText(true);

            TextArea local = payloadArea(i18n.text("ui.text.local_version"), render(i18n, row.item().localSnapshot()));
            TextArea remote = payloadArea(i18n.text("ui.text.remote_version"), render(i18n, row.item().remoteSnapshot()));

            choice.getItems().setAll(SyncConflictReviewSelection.Side.LOCAL, SyncConflictReviewSelection.Side.REMOTE);
            choice.setConverter(new StringConverter<>() {
                @Override public String toString(SyncConflictReviewSelection.Side side) {
                    if (side == null) return "";
                    return side == SyncConflictReviewSelection.Side.LOCAL
                            ? i18n.text("ui.text.local_version")
                            : i18n.text("ui.text.remote_version");
                }
                @Override public SyncConflictReviewSelection.Side fromString(String value) {
                    return i18n.text("ui.text.remote_version").equals(value)
                            ? SyncConflictReviewSelection.Side.REMOTE
                            : SyncConflictReviewSelection.Side.LOCAL;
                }
            });
            choice.setValue(row.choice());
            choice.setAccessibleText(i18n.text("ui.text.version_to_keep"));

            node.add(entity, 0, 0, 2, 1);
            node.add(reason, 0, 1, 2, 1);
            node.add(new Label(i18n.text("ui.text.local_version")), 0, 2);
            node.add(new Label(i18n.text("ui.text.remote_version")), 1, 2);
            node.add(local, 0, 3);
            node.add(remote, 1, 3);
            node.add(new Label(i18n.text("ui.text.save")), 0, 4);
            node.add(choice, 1, 4);
            GridPane.setHgrow(local, Priority.ALWAYS);
            GridPane.setHgrow(remote, Priority.ALWAYS);
        }

        private SyncConflictReviewModel.Row result() {
            return row.withChoice(choice.getValue());
        }

        private static String render(LocalizationService i18n, SyncConflictReviewItem.Text text) {
            return i18n.format(text.key(), text.arguments().toArray());
        }

        private static TextArea payloadArea(String accessibleName, String value) {
            TextArea area = new TextArea(value);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(4);
            area.setAccessibleText(accessibleName);
            return area;
        }
    }
}
