package com.myhomelibcorp.ui.navigation;

import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
@Slf4j
public class AlphabetToolbarController {

    private static final String CYRILLIC_ALPHABET = "АБВГҐДЕЁЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ";
    private static final String LATIN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    @FXML private HBox alphabetToolbarContainer;

    private Consumer<Character> onLetterSelected;
    private Button selectedButton;
    private Button allButton;

    @FXML
    public void initialize() {
        VBox alphabetPanel = createAlphabetPanel();
        alphabetToolbarContainer.getChildren().add(alphabetPanel);
        HBox.setHgrow(alphabetPanel, javafx.scene.layout.Priority.ALWAYS);

        alphabetToolbarContainer.setVisible(true);
        alphabetToolbarContainer.setManaged(true);

        log.info("AlphabetToolbar ініціалізовано");
    }

    private VBox createAlphabetPanel() {
        VBox panel = new VBox(2);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(2, 4, 2, 4));
        panel.getStyleClass().add("alphabet-panel");

        // Рядок 1: Всі + # + Кирилиця
        FlowPane row1 = new FlowPane();
        row1.setHgap(1);
        row1.setVgap(1);
        row1.setAlignment(Pos.CENTER);
        row1.setPadding(new Insets(1, 2, 1, 2));

        // Кнопка "Всі"
        Button allBtn = createLetterButton('*', "Всі", "alphabet-all");
        this.allButton = allBtn;
        allBtn.setTooltip(new Tooltip("Всі"));
        row1.getChildren().add(allBtn);

        // Кнопка "#" (не літери)
        Button nonAlphaBtn = createLetterButton('#', "#", "alphabet-non-alpha");
        nonAlphaBtn.setTooltip(new Tooltip("Не літери (цифри, символи)"));
        row1.getChildren().add(nonAlphaBtn);

        // Роздільник
        Label separator = new Label("|");
        separator.getStyleClass().add("alphabet-separator");
        row1.getChildren().add(separator);

        // Кирилиця
        for (char c : CYRILLIC_ALPHABET.toCharArray()) {
            row1.getChildren().add(createLetterButton(c, String.valueOf(c), null));
        }
        panel.getChildren().add(row1);

        // Рядок 2: Латиниця
        FlowPane row2 = new FlowPane();
        row2.setHgap(1);
        row2.setVgap(1);
        row2.setAlignment(Pos.CENTER);
        row2.setPadding(new Insets(1, 2, 1, 2));

        for (char c : LATIN_ALPHABET.toCharArray()) {
            row2.getChildren().add(createLetterButton(c, String.valueOf(c), null));
        }
        panel.getChildren().add(row2);

        return panel;
    }

    private Button createLetterButton(char letter, String text, String roleStyleClass) {
        Button btn = new Button(text);
        btn.getStyleClass().add("alphabet-button");
        if (roleStyleClass != null && !roleStyleClass.isBlank()) btn.getStyleClass().add(roleStyleClass);

        if (text.length() > 1) btn.setPrefWidth(40);
        else btn.setPrefWidth(28);
        btn.setPrefHeight(28);
        btn.setMinSize(26, 26);
        btn.setMaxSize(40, 32);

        btn.setOnAction(e -> {
            selectButton(btn);
            if (onLetterSelected != null) onLetterSelected.accept(letter);
        });
        return btn;
    }

    private void selectButton(Button button) {
        if (selectedButton != null && selectedButton != button) {
            selectedButton.getStyleClass().remove("alphabet-selected");
        }
        selectedButton = button;
        if (button != null && !button.getStyleClass().contains("alphabet-selected")) {
            button.getStyleClass().add("alphabet-selected");
        }
    }

    public void setOnLetterSelected(Consumer<Character> listener) {
        this.onLetterSelected = listener;
    }

    public char getSelectedLetter() {
        if (selectedButton != null) {
            String text = selectedButton.getText();
            if (text.length() == 1) {
                return text.charAt(0);
            }
            if (text.equals("Всі")) {
                return '*';
            }
            if (text.equals("#")) {
                return '#';
            }
        }
        return '*';
    }

    public void selectLetter(char letter) {
        for (javafx.scene.Node node : alphabetToolbarContainer.getChildren()) {
            if (node instanceof VBox) {
                for (javafx.scene.Node row : ((VBox) node).getChildren()) {
                    if (row instanceof FlowPane) {
                        for (javafx.scene.Node child : ((FlowPane) row).getChildren()) {
                            if (child instanceof Button) {
                                Button btn = (Button) child;
                                if (btn.getText().length() == 1 && btn.getText().charAt(0) == letter) {
                                    selectButton(btn);
                                    return;
                                }
                                if (letter == '*' && btn.getText().equals("Всі")) {
                                    selectButton(btn);
                                    return;
                                }
                                if (letter == '#' && btn.getText().equals("#")) {
                                    selectButton(btn);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
        selectAll();
    }

    public void selectAll() {
        for (javafx.scene.Node node : alphabetToolbarContainer.getChildren()) {
            if (node instanceof VBox) {
                for (javafx.scene.Node row : ((VBox) node).getChildren()) {
                    if (row instanceof FlowPane) {
                        for (javafx.scene.Node child : ((FlowPane) row).getChildren()) {
                            if (child instanceof Button) {
                                Button btn = (Button) child;
                                if (btn.getText().equals("Всі")) {
                                    selectButton(btn);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void clearSelection() {
        if (selectedButton != null) {
            selectedButton.getStyleClass().remove("alphabet-selected");
            selectedButton = null;
        }
    }
    /**
     * AUTHORS intentionally has no "load everything" action for large catalogues.
     */
    public void setAllOptionEnabled(boolean enabled) {
        if (allButton == null) return;
        allButton.setDisable(!enabled);
        allButton.setVisible(enabled);
        allButton.setManaged(enabled);
        if (!enabled && selectedButton == allButton) {
            clearSelection();
        }
    }

}