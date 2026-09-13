package com.myhomelibcorp.ui.customfield;

import com.myhomelibcorp.application.customfield.CustomFieldService;
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
        List<String> actions = List.of("Створити поле", "Редагувати поле", "Видалити поле");
        ChoiceDialog<String> choice = new ChoiceDialog<>(actions.getFirst(), actions);
        choice.initOwner(owner);
        choice.setTitle("Користувацькі поля");
        choice.setHeaderText("Керування визначеннями користувацьких полів");
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
            dialogs.showInfo("Користувацькі поля", "Спочатку створіть хоча б одне користувацьке поле.");
            return;
        }
        Map<Long, String> existing = service.valueTexts(bookId);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Користувацькі поля книги");
        dialog.setHeaderText("Значення користувацьких полів");
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
        background.submit(() -> { service.saveValues(bookId, values); return null; })
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (error != null) dialogs.showError("Користувацькі поля", rootMessage(error));
                }));
    }

    private void create(Window owner) {
        DefinitionInput input = definitionDialog(owner, null);
        if (input == null) return;
        background.submit(() -> service.saveDefinition(null, input.name(), input.type(), input.options()))
                .whenComplete((value, error) -> Platform.runLater(() -> {
                    if (error != null) dialogs.showError("Користувацькі поля", rootMessage(error));
                }));
    }

    private void edit(Window owner) {
        DefinitionView selected = chooseDefinition(owner, "Редагувати поле");
        if (selected == null) return;
        DefinitionInput input = definitionDialog(owner, selected);
        if (input == null) return;
        background.submit(() -> service.saveDefinition(selected.id(), input.name(), input.type(), input.options()))
                .whenComplete((value, error) -> Platform.runLater(() -> {
                    if (error != null) dialogs.showError("Користувацькі поля", rootMessage(error));
                }));
    }

    private void delete(Window owner) {
        DefinitionView selected = chooseDefinition(owner, "Видалити поле");
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Видалити поле «" + selected.name() + "» разом із усіма його значеннями?",
                ButtonType.YES, ButtonType.NO);
        confirm.initOwner(owner); confirm.setTitle("Користувацькі поля");
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        background.submit(() -> { service.deleteDefinitionCascade(selected.id()); return null; })
                .whenComplete((value, error) -> Platform.runLater(() -> {
                    if (error != null) dialogs.showError("Користувацькі поля", rootMessage(error));
                }));
    }

    private DefinitionView chooseDefinition(Window owner, String title) {
        List<DefinitionView> definitions = service.definitionViews();
        if (definitions.isEmpty()) { dialogs.showInfo("Користувацькі поля", "Немає створених полів."); return null; }
        Map<String, DefinitionView> byLabel = new LinkedHashMap<>();
        for (DefinitionView definition : definitions) {
            byLabel.put(definition.name() + " (" + definition.type() + ")", definition);
        }
        List<String> labels = new ArrayList<>(byLabel.keySet());
        ChoiceDialog<String> dialog = new ChoiceDialog<>(labels.getFirst(), labels);
        dialog.initOwner(owner); dialog.setTitle(title); dialog.setHeaderText("Виберіть поле");
        return dialog.showAndWait().map(byLabel::get).orElse(null);
    }

    private DefinitionInput definitionDialog(Window owner, DefinitionView current) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(owner); dialog.setTitle("Користувацьке поле");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField name = new TextField(current == null ? "" : current.name());
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("TEXT", "NUMBER", "BOOL", "DATE", "ENUM"));
        type.setValue(current == null ? "TEXT" : current.type());
        TextField options = new TextField(current == null ? "" : String.join(", ", current.enumOptions()));
        options.setPromptText("Для ENUM: варіанти через кому");
        GridPane grid = new GridPane(); grid.setHgap(10); grid.setVgap(8);
        grid.addRow(0, new Label("Назва"), name); grid.addRow(1, new Label("Тип"), type); grid.addRow(2, new Label("ENUM"), options);
        dialog.getDialogPane().setContent(grid);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        List<String> enumOptions = "ENUM".equals(type.getValue())
                ? Arrays.stream(options.getText().split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList() : List.of();
        try { return new DefinitionInput(name.getText(), type.getValue(), enumOptions); }
        catch (RuntimeException e) { dialogs.showError("Користувацькі поля", e.getMessage()); return null; }
    }

    private static Node controlFor(DefinitionView d, String value) {
        return switch (d.type()) {
            case "BOOL" -> { CheckBox box = new CheckBox(); box.setSelected(Boolean.parseBoolean(value)); yield box; }
            case "DATE" -> { DatePicker picker = new DatePicker(); if (!value.isBlank()) try { picker.setValue(LocalDate.parse(value)); } catch (RuntimeException ignored) { } yield picker; }
            case "ENUM" -> { ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(d.enumOptions())); box.setEditable(false); if (!value.isBlank()) box.setValue(value); yield box; }
            case "TEXT", "NUMBER" -> new TextField(value);
            default -> throw new IllegalArgumentException("Unsupported custom field type: " + d.type());
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
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private record DefinitionInput(String name, String type, List<String> options) { }
}
