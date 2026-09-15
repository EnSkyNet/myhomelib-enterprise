package com.myhomelibcorp.ui.annotation;

import com.myhomelibcorp.application.annotation.AnnotationManagerType;
import com.myhomelibcorp.application.annotation.AnnotationService;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Reusable create/edit dialog for Reader and Annotation Manager. */
@Component
@RequiredArgsConstructor
public class AnnotationEditorDialog {
    private final LocalizationService i18n;

    public Optional<Result> showCreateNote(
            Window owner,
            String quote,
            String color,
            Set<String> tags,
            Collection<String> knownTags
    ) {
        String initialColor = color == null || color.isBlank() ? AnnotationService.DEFAULT_COLOR : color;
        return show(owner, Mode.CREATE, AnnotationManagerType.NOTE, quote, "", initialColor, tags, knownTags);
    }

    public Optional<Result> showEdit(
            Window owner,
            AnnotationManagerType type,
            String quote,
            String note,
            String color,
            Set<String> tags,
            Collection<String> knownTags
    ) {
        return show(owner, Mode.EDIT, type, quote, note, color, tags, knownTags);
    }

    private Optional<Result> show(
            Window owner,
            Mode mode,
            AnnotationManagerType type,
            String quote,
            String noteValue,
            String colorValue,
            Set<String> tagValues,
            Collection<String> knownTags
    ) {
        Dialog<Result> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(i18n.text(mode == Mode.CREATE
                ? "ui.reader.annotation.editor.create_title"
                : "ui.reader.annotation.editor.edit_title"));
        dialog.setHeaderText(type == AnnotationManagerType.NOTE
                ? i18n.text("ui.reader.annotation.editor.note_header")
                : i18n.text("ui.reader.annotation.editor.highlight_header"));

        ButtonType saveType = new ButtonType(i18n.text("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(saveType, ButtonType.CANCEL);

        TextArea quoteArea = new TextArea(quote == null ? "" : quote);
        quoteArea.setEditable(false);
        quoteArea.setWrapText(true);
        quoteArea.setPrefRowCount(3);
        quoteArea.setMaxHeight(110);
        quoteArea.setAccessibleText(i18n.text("ui.reader.annotation.editor.quote"));

        Button copyQuote = new Button(i18n.text("ui.reader.annotation.copy_quote"));
        copyQuote.setOnAction(event -> copyToClipboard(quoteArea.getText()));

        HBox quoteActions = new HBox(8, copyQuote);
        quoteActions.setAlignment(Pos.CENTER_RIGHT);

        Label noteLabel = new Label(i18n.text("ui.reader.annotation.editor.note"));
        TextArea noteArea = new TextArea(noteValue == null ? "" : noteValue);
        noteArea.setWrapText(true);
        noteArea.setPrefRowCount(6);
        noteArea.setPromptText(i18n.text("ui.reader.annotation.editor.note_prompt"));
        noteArea.setAccessibleText(i18n.text("ui.reader.annotation.editor.note"));

        ColorPicker colorPicker = new ColorPicker(parseColor(colorValue));
        colorPicker.setAccessibleText(i18n.text("ui.reader.annotation.editor.color"));

        TextField tagsField = new TextField(joinTags(tagValues));
        tagsField.setPromptText(i18n.text("ui.reader.annotation.editor.tags_prompt"));
        tagsField.setAccessibleText(i18n.text("ui.reader.annotation.editor.tags"));

        ComboBox<String> tagSuggestions = new ComboBox<>();
        tagSuggestions.setPromptText(i18n.text("ui.reader.annotation.editor.tag_suggestion"));
        tagSuggestions.setItems(FXCollections.observableArrayList(normalizeKnownTags(knownTags)));
        tagSuggestions.setPrefWidth(180);
        tagSuggestions.setOnAction(event -> {
            String selected = tagSuggestions.getValue();
            if (selected != null && !selected.isBlank()) {
                tagsField.setText(appendTag(tagsField.getText(), selected));
                tagSuggestions.getSelectionModel().clearSelection();
            }
        });

        HBox tagRow = new HBox(8, tagsField, tagSuggestions);
        HBox.setHgrow(tagsField, Priority.ALWAYS);

        Label validation = new Label();
        validation.setStyle("-fx-text-fill: -mhl-danger;");
        validation.setWrapText(true);
        validation.setManaged(false);
        validation.setVisible(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label(i18n.text("ui.reader.annotation.editor.quote")), 0, 0);
        grid.add(quoteArea, 1, 0);
        grid.add(quoteActions, 1, 1);
        grid.add(noteLabel, 0, 2);
        grid.add(noteArea, 1, 2);
        grid.add(new Label(i18n.text("ui.reader.annotation.editor.color")), 0, 3);
        grid.add(colorPicker, 1, 3);
        grid.add(new Label(i18n.text("ui.reader.annotation.editor.tags")), 0, 4);
        grid.add(tagRow, 1, 4);
        grid.add(validation, 1, 5);
        GridPane.setHgrow(quoteArea, Priority.ALWAYS);
        GridPane.setHgrow(noteArea, Priority.ALWAYS);
        GridPane.setHgrow(tagRow, Priority.ALWAYS);

        boolean noteType = type == AnnotationManagerType.NOTE;
        noteLabel.setManaged(noteType);
        noteLabel.setVisible(noteType);
        noteArea.setManaged(noteType);
        noteArea.setVisible(noteType);

        VBox wrapper = new VBox(8, grid);
        wrapper.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(wrapper);
        dialog.getDialogPane().setPrefWidth(680);

        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        Runnable refreshValidation = () -> {
            boolean invalid = type == AnnotationManagerType.NOTE && noteArea.getText().trim().isEmpty();
            saveButton.setDisable(invalid);
            validation.setText(invalid ? i18n.text("ui.annotations.note_required") : "");
            validation.setManaged(invalid);
            validation.setVisible(invalid);
        };
        noteArea.textProperty().addListener((obs, oldValue, newValue) -> refreshValidation.run());
        refreshValidation.run();

        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && event.isControlDown() && !saveButton.isDisabled()) {
                if (saveButton instanceof Button button) button.fire();
                event.consume();
            }
        });

        dialog.setResultConverter(button -> {
            if (button != saveType) return null;
            String selectedColor = toHex(colorPicker.getValue());
            return new Result(
                    noteArea.getText() == null ? "" : noteArea.getText().trim(),
                    selectedColor,
                    parseTags(tagsField.getText()));
        });

        Platform.runLater(noteArea::requestFocus);
        return dialog.showAndWait();
    }

    private static Color parseColor(String value) {
        try {
            return Color.web(value == null || value.isBlank() ? AnnotationService.DEFAULT_COLOR : value);
        } catch (RuntimeException ignored) {
            return Color.web(AnnotationService.DEFAULT_COLOR);
        }
    }

    private static String toHex(Color color) {
        Color c = color == null ? Color.web(AnnotationService.DEFAULT_COLOR) : color;
        int r = (int) Math.round(c.getRed() * 255.0);
        int g = (int) Math.round(c.getGreen() * 255.0);
        int b = (int) Math.round(c.getBlue() * 255.0);
        int a = (int) Math.round(c.getOpacity() * 255.0);
        return a >= 255
                ? String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b)
                : String.format(Locale.ROOT, "#%02X%02X%02X%02X", r, g, b, a);
    }

    private static List<String> normalizeKnownTags(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : tags) {
            if (value == null || value.isBlank()) continue;
            unique.add(value.trim());
        }
        List<String> result = new ArrayList<>(unique);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    private static String joinTags(Set<String> tags) {
        return tags == null || tags.isEmpty() ? "" : String.join(", ", tags);
    }

    private static String appendTag(String current, String value) {
        Set<String> tags = new LinkedHashSet<>(parseTags(current));
        tags.add(value.trim());
        return String.join(", ", tags);
    }

    public static Set<String> parseTags(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : value.split("[,;]")) {
            String tag = part.trim();
            if (!tag.isEmpty()) result.add(tag);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(result));
    }

    private static void copyToClipboard(String text) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text == null ? "" : text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private enum Mode { CREATE, EDIT }

    public record Result(String note, String color, Set<String> tags) {
        public Result {
            note = note == null ? "" : note;
            color = color == null || color.isBlank() ? AnnotationService.DEFAULT_COLOR : color;
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }
    }
}
