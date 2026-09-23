package com.myhomelibcorp.ui.customfield;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.customfield.CustomFieldService;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.customfield.CustomFieldService.DefinitionView;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

@Component
public class CustomFieldUiService {
    private final CustomFieldService service;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;

    public CustomFieldUiService(CustomFieldService service, UiBackgroundExecutor background, DialogService dialogs) {
        this.service = service;
        this.background = background;
        this.dialogs = dialogs;
    }

    public void manage(Window owner) {
        List<String> actions = List.of(UiText.text("ui.java.custom_field_ui_service.f5cee4d194"), UiText.text("ui.java.custom_field_ui_service.f8e8c9c817"), UiText.text("ui.java.custom_field_ui_service.1eb483b0e2"));
        ChoiceDialog<String> choice = new ChoiceDialog<>(actions.getFirst(), actions);
        DialogLocalization.prepare(choice);
        choice.initOwner(owner);
        choice.setTitle(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"));
        choice.setHeaderText(UiText.text("ui.java.custom_field_ui_service.f9be29f5a9"));
        choice.showAndWait().ifPresent(action -> {
            if (actions.get(0).equals(action)) create(owner);
            else if (actions.get(1).equals(action)) edit(owner);
            else delete(owner);
        });
    }

    public void editBookValues(Window owner, BookId bookId) {
        if (bookId == null) return;
        List<DefinitionView> definitions = service.definitionViews();
        if (definitions.isEmpty()) {
            dialogs.showInfo(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"), UiText.text("ui.java.custom_field_ui_service.d9c48bfdc2"));
            return;
        }
        Map<Long, String> existing = service.valueTexts(bookId);
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.initOwner(owner);
        dialog.setTitle(UiText.text("ui.java.custom_field_ui_service.6cddda9f0d"));
        dialog.setHeaderText(UiText.text("ui.java.custom_field_ui_service.e04e6f52dc"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8);
        Map<Long, Node> controls = new LinkedHashMap<>();
        int row = 0;
        for (DefinitionView definition : definitions) {
            grid.add(new Label(definition.name()), 0, row);
            String current = Optional.ofNullable(existing.get(definition.id())).orElse("");
            Node control = controlFor(definition, current);
            controls.put(definition.id(), control);
            grid.add(control, 1, row++);
        }
        ScrollPane scroll = new ScrollPane(grid); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(420); scroll.setPrefViewportWidth(560);
        dialog.getDialogPane().setContent(scroll);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        Map<Long, String> values = new LinkedHashMap<>();
        for (DefinitionView definition : definitions) values.put(definition.id(), valueOf(controls.get(definition.id())));
        background.submitCollectionScoped(LibraryOperationType.EDIT, () -> { service.saveValues(bookId, values); return null; })
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (error != null) dialogs.showError(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"), rootMessage(error));
                }));
    }

    private void create(Window owner) {
        DefinitionInput input = definitionDialog(owner, null);
        if (input == null) return;
        background.submitCollectionScoped(LibraryOperationType.EDIT, () -> service.saveDefinition(null, input.name(), input.type(), input.options()))
                .whenComplete((value, error) -> Platform.runLater(() -> {
                    if (error != null) dialogs.showError(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"), rootMessage(error));
                }));
    }

    private void edit(Window owner) {
        DefinitionView selected = chooseDefinition(owner, UiText.text("ui.java.custom_field_ui_service.f8e8c9c817"));
        if (selected == null) return;
        DefinitionInput input = definitionDialog(owner, selected);
        if (input == null) return;
        background.submitCollectionScoped(LibraryOperationType.EDIT, () -> service.saveDefinition(selected.id(), input.name(), input.type(), input.options()))
                .whenComplete((value, error) -> Platform.runLater(() -> {
                    if (error != null) dialogs.showError(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"), rootMessage(error));
                }));
    }

    private void delete(Window owner) {
        DefinitionView selected = chooseDefinition(owner, UiText.text("ui.java.custom_field_ui_service.1eb483b0e2"));
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                UiText.text("ui.java.custom_field_ui_service.e57c59e10d") + selected.name() + UiText.text("ui.java.custom_field_ui_service.7f391466ca"),
                ButtonType.YES, ButtonType.NO);
        DialogLocalization.prepare(confirm);
        confirm.initOwner(owner); confirm.setTitle(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"));
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        background.submitCollectionScoped(LibraryOperationType.EDIT, () -> { service.deleteDefinitionCascade(selected.id()); return null; })
                .whenComplete((value, error) -> Platform.runLater(() -> {
                    if (error != null) dialogs.showError(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"), rootMessage(error));
                }));
    }

    private DefinitionView chooseDefinition(Window owner, String title) {
        List<DefinitionView> definitions = service.definitionViews();
        if (definitions.isEmpty()) { dialogs.showInfo(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"), UiText.text("ui.java.custom_field_ui_service.0691ec0b42")); return null; }
        Map<String, DefinitionView> byLabel = new LinkedHashMap<>();
        for (DefinitionView definition : definitions) {
            byLabel.put(definition.name() + " (" + typeLabel(definition.type()) + ")", definition);
        }
        List<String> labels = new ArrayList<>(byLabel.keySet());
        ChoiceDialog<String> dialog = new ChoiceDialog<>(labels.getFirst(), labels);
        DialogLocalization.prepare(dialog);
        dialog.initOwner(owner); dialog.setTitle(title); dialog.setHeaderText(UiText.text("ui.java.custom_field_ui_service.b4b5ba719c"));
        return dialog.showAndWait().map(byLabel::get).orElse(null);
    }

    private DefinitionInput definitionDialog(Window owner, DefinitionView current) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog); dialog.initOwner(owner); dialog.setTitle(UiText.text("ui.java.custom_field_ui_service.317922f7fd"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField name = new TextField(current == null ? "" : current.name());
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("TEXT", "NUMBER", "BOOL", "DATE", "ENUM"));
        type.setValue(current == null ? "TEXT" : current.type());
        type.setCellFactory(list -> customFieldTypeCell());
        type.setButtonCell(customFieldTypeCell());
        TextField options = new TextField(current == null ? "" : String.join(", ", current.enumOptions()));
        options.setPromptText(UiText.text("ui.java.custom_field_ui_service.55f947385d"));
        GridPane grid = new GridPane(); grid.setHgap(10); grid.setVgap(8);
        grid.addRow(0, new Label(UiText.text("ui.bulkedit.field.title")), name); grid.addRow(1, new Label(UiText.text("ui.annotations.column.type")), type); grid.addRow(2, new Label(UiText.text("ui.java.custom_field_ui_service.9e840b2e78")), options);
        dialog.getDialogPane().setContent(grid);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        List<String> enumOptions = "ENUM".equals(type.getValue())
                ? Arrays.stream(options.getText().split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList() : List.of();
        try { return new DefinitionInput(name.getText(), type.getValue(), enumOptions); }
        catch (RuntimeException e) { dialogs.showError(UiText.text("ui.java.custom_field_ui_service.42f9479a3a"), UiExceptionSupport.message(e)); return null; }
    }

    private static ListCell<String> customFieldTypeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : typeLabel(item));
            }
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "TEXT" -> UiText.text("ui.java.custom_field_ui_service.93970437e2");
            case "NUMBER" -> UiText.text("ui.java.custom_field_ui_service.4d0f3e5c74");
            case "BOOL" -> UiText.text("ui.java.custom_field_ui_service.6fbdd1345e");
            case "DATE" -> UiText.text("ui.fxml.followed_authors_workspace.date");
            case "ENUM" -> UiText.text("ui.java.custom_field_ui_service.09565c73df");
            default -> type;
        };
    }

    private static Node controlFor(DefinitionView d, String value) {
        return switch (d.type()) {
            case "BOOL" -> { CheckBox box = new CheckBox(); box.setSelected(Boolean.parseBoolean(value)); yield box; }
            case "DATE" -> { DatePicker picker = new DatePicker(); if (!value.isBlank()) try { picker.setValue(LocalDate.parse(value)); } catch (RuntimeException ignored) { } yield picker; }
            case "ENUM" -> { ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(d.enumOptions())); box.setEditable(false); if (!value.isBlank()) box.setValue(value); yield box; }
            case "TEXT", "NUMBER" -> new TextField(value);
            default -> throw new IllegalArgumentException(UiText.text("ui.java.custom_field_ui_service.6903b699b1") + d.type());
        };
    }

    private static String valueOf(Node node) {
        if (node instanceof CheckBox box) return Boolean.toString(box.isSelected());
        if (node instanceof DatePicker picker) return picker.getValue() == null ? "" : picker.getValue().toString();
        if (node instanceof ComboBox<?> box) return box.getValue() == null ? "" : box.getValue().toString();
        if (node instanceof TextField text) return text.getText();
        return "";
    }

    private static String rootMessage(Throwable error) {
        return UiExceptionSupport.message(error);
    }

    private record DefinitionInput(String name, String type, List<String> options) { }
}
