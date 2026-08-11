package com.myhomelibcorp.ui.navigation;

import javafx.geometry.Pos;
import javafx.scene.control.Button;

/**
 * Custom button for alphabet navigation.
 * Larger size for better visibility.
 */
public class AlphabetButton extends Button {

    private static final String STYLE_NORMAL =
            "-fx-background-color: transparent; " +
                    "-fx-text-fill: #333333; " +
                    "-fx-padding: 4 6 4 6; " +
                    "-fx-font-size: 13px; " +
                    "-fx-font-weight: bold; " +
                    "-fx-cursor: hand; " +
                    "-fx-border-color: transparent; " +
                    "-fx-border-radius: 4; " +
                    "-fx-background-radius: 4; " +
                    "-fx-min-width: 30; " +
                    "-fx-min-height: 30;";

    private static final String STYLE_SELECTED =
            "-fx-background-color: #2196F3; " +
                    "-fx-text-fill: white; " +
                    "-fx-padding: 4 6 4 6; " +
                    "-fx-font-size: 13px; " +
                    "-fx-font-weight: bold; " +
                    "-fx-cursor: hand; " +
                    "-fx-border-color: #1976D2; " +
                    "-fx-border-radius: 4; " +
                    "-fx-background-radius: 4; " +
                    "-fx-min-width: 30; " +
                    "-fx-min-height: 30;";

    private static final String STYLE_HOVER =
            "-fx-background-color: #E3F2FD; " +
                    "-fx-text-fill: #1976D2; " +
                    "-fx-padding: 4 6 4 6; " +
                    "-fx-font-size: 13px; " +
                    "-fx-font-weight: bold; " +
                    "-fx-cursor: hand; " +
                    "-fx-border-color: #BBDEFB; " +
                    "-fx-border-radius: 4; " +
                    "-fx-background-radius: 4; " +
                    "-fx-min-width: 30; " +
                    "-fx-min-height: 30;";

    private final char letter;
    private boolean selected;

    public AlphabetButton(char letter) {
        this.letter = letter;
        setText(String.valueOf(letter));
        setAlignment(Pos.CENTER);
        setStyle(STYLE_NORMAL);

        setOnMouseEntered(e -> {
            if (!selected) {
                setStyle(STYLE_HOVER);
            }
        });
        setOnMouseExited(e -> {
            if (!selected) {
                setStyle(STYLE_NORMAL);
            }
        });
    }

    public char getLetter() {
        return letter;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        setStyle(selected ? STYLE_SELECTED : STYLE_NORMAL);
    }

    @Override
    public String toString() {
        return String.valueOf(letter);
    }
}