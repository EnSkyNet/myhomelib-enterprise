package com.myhomelibcorp.ui.navigation;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Panel with letter buttons for alphabet navigation.
 * Full width with wrapping support.
 */
@Slf4j
public class AlphabetToolbar extends VBox {

    private static final String CYRILLIC_ALPHABET = "АБВГДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ";
    private static final String LATIN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final FlowPane cyrillicButtons;
    private final FlowPane latinButtons;
    private final FlowPane specialButtons;
    private final List<AlphabetButton> allButtons = new ArrayList<>();

    @Getter
    private AlphabetButton selectedButton;
    private Consumer<Character> onLetterSelected;

    public AlphabetToolbar() {
        setSpacing(2);
        setPadding(new Insets(4, 8, 4, 8));
        setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);

        // Кирилиця - перший рядок
        cyrillicButtons = createButtonRow(CYRILLIC_ALPHABET, "Cyrillic");
        cyrillicButtons.setMaxWidth(Double.MAX_VALUE);

        // Латиниця - другий рядок
        latinButtons = createButtonRow(LATIN_ALPHABET, "Latin");
        latinButtons.setMaxWidth(Double.MAX_VALUE);

        // Спеціальні символи - третій рядок
        specialButtons = createSpecialButtons();
        specialButtons.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(cyrillicButtons, latinButtons, specialButtons);

        // Кожен рядок займає всю ширину
        for (var child : getChildren()) {
            if (child instanceof FlowPane) {
                ((FlowPane) child).prefWidthProperty().bind(widthProperty());
            }
        }

        log.debug("AlphabetToolbar created: cyrillic={}, latin={}, special={}",
                cyrillicButtons.getChildren().size(),
                latinButtons.getChildren().size(),
                specialButtons.getChildren().size());
    }

    private FlowPane createButtonRow(String alphabet, String tooltipText) {
        FlowPane row = new FlowPane();
        row.setHgap(2);
        row.setVgap(2);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(2, 4, 2, 4));
        row.setPrefWrapLength(Double.MAX_VALUE);

        for (char c : alphabet.toCharArray()) {
            AlphabetButton btn = createButton(c);
            row.getChildren().add(btn);
            allButtons.add(btn);
        }

        Tooltip tooltip = new Tooltip(tooltipText);
        Tooltip.install(row, tooltip);

        return row;
    }

    private FlowPane createSpecialButtons() {
        FlowPane row = new FlowPane();
        row.setHgap(4);
        row.setVgap(2);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(2, 4, 2, 4));
        row.setPrefWrapLength(Double.MAX_VALUE);

        // Всі (*)
        AlphabetButton allBtn = createButton('*');
        allBtn.setTooltip(new Tooltip("All"));
        allBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 4 12 4 12; -fx-border-radius: 4; -fx-background-radius: 4;");
        row.getChildren().add(allBtn);
        allButtons.add(allBtn);

        // Не-літери (#)
        AlphabetButton nonAlphaBtn = createButton('#');
        nonAlphaBtn.setTooltip(new Tooltip("Non-letters (digits, symbols)"));
        nonAlphaBtn.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 4 12 4 12; -fx-border-radius: 4; -fx-background-radius: 4;");
        row.getChildren().add(nonAlphaBtn);
        allButtons.add(nonAlphaBtn);

        return row;
    }

    private AlphabetButton createButton(char letter) {
        AlphabetButton btn = new AlphabetButton(letter);
        btn.setPrefSize(30, 30);
        btn.setMinSize(28, 28);
        btn.setMaxSize(32, 32);
        btn.setOnAction(e -> {
            selectButton(btn);
            if (onLetterSelected != null) {
                onLetterSelected.accept(letter);
            }
        });
        return btn;
    }

    public void selectButton(AlphabetButton button) {
        if (selectedButton != null && selectedButton != button) {
            selectedButton.setSelected(false);
        }
        selectedButton = button;
        if (button != null) {
            button.setSelected(true);
        }
    }

    public void selectLetter(char letter) {
        for (AlphabetButton btn : allButtons) {
            if (btn.getLetter() == letter) {
                selectButton(btn);
                return;
            }
        }
        clearSelection();
    }

    public void clearSelection() {
        if (selectedButton != null) {
            selectedButton.setSelected(false);
            selectedButton = null;
        }
    }

    public void setOnLetterSelected(Consumer<Character> listener) {
        this.onLetterSelected = listener;
    }

    public int getButtonCount() {
        return allButtons.size();
    }
}