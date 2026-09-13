package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.usecase.search.LoadSmartCollectionDefinitionUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSmartCollectionUseCase;
import com.myhomelibcorp.application.usecase.search.SmartCollectionDefinition;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class SmartCollectionDialogController {
    private static final int MAX_RULES = 20;

    private final SaveSmartCollectionUseCase saveUseCase;
    private final LoadSmartCollectionDefinitionUseCase loadUseCase;
    private final DialogService dialogs;
    private final LocalizationService i18n;

    @FXML private TextField nameField;
    @FXML private CheckBox pinnedCheck;
    @FXML private ComboBox<Mode> modeCombo;
    @FXML private ComboBox<Sort> sortCombo;
    @FXML private ComboBox<Direction> directionCombo;
    @FXML private TextField limitField;
    @FXML private VBox rulesBox;
    @FXML private Button addRuleButton;

    private Runnable onSaved = () -> { };
    private String editingId;

    @FXML
    public void initialize() {
        modeCombo.getItems().setAll(Mode.values());
        sortCombo.getItems().setAll(Sort.values());
        directionCombo.getItems().setAll(Direction.values());
        modeCombo.setConverter(enumConverter("ui.smart_collection.mode."));
        sortCombo.setConverter(enumConverter("ui.smart_collection.sort."));
        directionCombo.setConverter(enumConverter("ui.smart_collection.direction."));
        modeCombo.setValue(Mode.AND);
        sortCombo.setValue(Sort.TITLE);
        directionCombo.setValue(Direction.ASC);
        limitField.setText("10000");
        addRule(Field.TITLE, Operator.CONTAINS, "", null);
    }

    public void setOnSaved(Runnable callback) {
        this.onSaved = callback == null ? () -> { } : callback;
    }

    public void edit(String savedSearchId) {
        if (savedSearchId == null || savedSearchId.isBlank()) return;
        SmartCollectionDefinition definition = loadUseCase.execute(savedSearchId);
        editingId = definition.id();
        nameField.setText(definition.name());
        nameField.setDisable(true);
        pinnedCheck.setSelected(definition.pinned());
        modeCombo.setValue(Mode.valueOf(definition.mode()));
        sortCombo.setValue(Sort.valueOf(definition.sort()));
        directionCombo.setValue(Direction.valueOf(definition.direction()));
        limitField.setText(Integer.toString(definition.maxResults()));
        rulesBox.getChildren().clear();
        for (SmartCollectionDefinition.Rule rule : definition.rules()) {
            addRule(Field.valueOf(rule.field()), Operator.valueOf(rule.operator()), rule.value(), rule.secondValue());
        }
    }

    @FXML
    private void onAddRule() {
        if (rulesBox.getChildren().size() >= MAX_RULES) {
            dialogs.showWarning(i18n.text("common.warning"),
                    i18n.format("ui.smart_collection.max_rules", MAX_RULES));
            return;
        }
        addRule(Field.TITLE, Operator.CONTAINS, "", null);
    }

    @FXML
    private void onSave() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            dialogs.showWarning(i18n.text("common.warning"), i18n.text("ui.smart_collection.name_required"));
            return;
        }
        try {
            List<SmartCollectionDefinition.Rule> rules = new ArrayList<>();
            for (var node : rulesBox.getChildren()) {
                if (node instanceof RuleRow row) rules.add(row.toRule());
            }
            int maxResults = Integer.parseInt(limitField.getText().trim());
            SmartCollectionDefinition definition = new SmartCollectionDefinition(
                    editingId, name, pinnedCheck.isSelected(), modeCombo.getValue().name(),
                    sortCombo.getValue().name(), directionCombo.getValue().name(), maxResults, rules);
            saveUseCase.execute(definition);
            onSaved.run();
            close();
        } catch (NumberFormatException e) {
            dialogs.showWarning(i18n.text("common.warning"), i18n.text("ui.smart_collection.limit_invalid"));
        } catch (IllegalArgumentException e) {
            dialogs.showWarning(i18n.text("common.warning"), e.getMessage());
        } catch (Exception e) {
            dialogs.showError(i18n.text("common.error"), i18n.format("ui.smart_collection.save_error", rootMessage(e)));
        }
    }

    @FXML
    private void onCancel() { close(); }

    private void close() {
        if (rulesBox != null && rulesBox.getScene() != null && rulesBox.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    private void addRule(Field field, Operator operator, String value, String secondValue) {
        RuleRow row = new RuleRow(field, operator, value, secondValue);
        rulesBox.getChildren().add(row);
        addRuleButton.setDisable(rulesBox.getChildren().size() >= MAX_RULES);
    }

    private final class RuleRow extends HBox {
        private final ComboBox<Field> field = new ComboBox<>();
        private final ComboBox<Operator> operator = new ComboBox<>();
        private final TextField value = new TextField();
        private final TextField second = new TextField();
        private final Button remove = new Button("×");

        private RuleRow(Field initialField, Operator initialOperator, String initialValue, String initialSecond) {
            setSpacing(8);
            field.getItems().setAll(Field.values());
            field.setConverter(enumConverter("ui.smart_collection.field."));
            operator.setConverter(enumConverter("ui.smart_collection.operator."));
            field.setPrefWidth(150);
            operator.setPrefWidth(150);
            value.setPromptText(i18n.text("ui.smart_collection.value"));
            second.setPromptText(i18n.text("ui.smart_collection.value_to"));
            HBox.setHgrow(value, Priority.ALWAYS);
            HBox.setHgrow(second, Priority.ALWAYS);
            getChildren().addAll(field, operator, value, second, remove);

            field.setValue(initialField);
            refreshOperators(initialOperator);
            value.setText(initialValue == null ? "" : initialValue);
            second.setText(initialSecond == null ? "" : initialSecond);
            field.valueProperty().addListener((obs, old, current) -> refreshOperators(null));
            operator.valueProperty().addListener((obs, old, current) -> updateValueVisibility());
            remove.setOnAction(e -> {
                if (rulesBox.getChildren().size() <= 1) {
                    dialogs.showWarning(i18n.text("common.warning"), i18n.text("ui.smart_collection.one_rule_required"));
                    return;
                }
                rulesBox.getChildren().remove(this);
                addRuleButton.setDisable(false);
            });
            updateValueVisibility();
        }

        private void refreshOperators(Operator preferred) {
            Field current = field.getValue();
            List<Operator> allowed = allowedOperators(current);
            operator.getItems().setAll(allowed);
            operator.setValue(preferred != null && allowed.contains(preferred) ? preferred : allowed.get(0));
            updateValueVisibility();
        }

        private void updateValueVisibility() {
            Operator op = operator.getValue();
            boolean noValue = op == Operator.IS_TRUE || op == Operator.IS_FALSE;
            boolean between = op == Operator.BETWEEN;
            value.setVisible(!noValue);
            value.setManaged(!noValue);
            second.setVisible(between);
            second.setManaged(between);
        }

        private SmartCollectionDefinition.Rule toRule() {
            return new SmartCollectionDefinition.Rule(field.getValue().name(), operator.getValue().name(),
                    value.isManaged() ? value.getText() : null,
                    second.isManaged() ? second.getText() : null);
        }
    }

    private static List<Operator> allowedOperators(Field field) {
        if (field == Field.LOCAL) {
            return List.of(Operator.IS_TRUE, Operator.IS_FALSE);
        }
        if (field == Field.YEAR || field == Field.PROGRESS || field == Field.RATING) {
            return List.of(Operator.EQUALS, Operator.NOT_EQUALS, Operator.AT_LEAST, Operator.AT_MOST, Operator.BETWEEN);
        }
        if (field == Field.LANGUAGE || field == Field.FORMAT) {
            return List.of(Operator.EQUALS, Operator.NOT_EQUALS);
        }
        return List.of(Operator.CONTAINS, Operator.EQUALS, Operator.NOT_EQUALS);
    }

    private <E extends Enum<E>> StringConverter<E> enumConverter(String prefix) {
        return new StringConverter<>() {
            @Override public String toString(E value) {
                return value == null ? "" : i18n.text(prefix + value.name().toLowerCase(Locale.ROOT));
            }
            @Override public E fromString(String string) { return null; }
        };
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private enum Mode { AND, OR }
    private enum Sort { TITLE, AUTHOR, SERIES, YEAR, RATING, PROGRESS, ADDED }
    private enum Direction { ASC, DESC }
    private enum Field { TITLE, AUTHOR, SERIES, GENRE, KEYWORD, PUBLISHER, LANGUAGE, FORMAT, YEAR, PROGRESS, RATING, LOCAL }
    private enum Operator { CONTAINS, EQUALS, NOT_EQUALS, AT_LEAST, AT_MOST, BETWEEN, IS_TRUE, IS_FALSE }
}
