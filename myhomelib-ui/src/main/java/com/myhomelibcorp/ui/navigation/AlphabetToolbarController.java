package com.myhomelibcorp.ui.navigation;

import javafx.fxml.FXML;
import javafx.scene.layout.HBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AlphabetToolbarController {

    private final AlphabetToolbar alphabetToolbar = new AlphabetToolbar();

    @FXML
    private HBox alphabetToolbarContainer;

    @FXML
    public void initialize() {
        alphabetToolbarContainer.getChildren().add(alphabetToolbar);
        HBox.setHgrow(alphabetToolbar, javafx.scene.layout.Priority.ALWAYS);
        log.info("AlphabetToolbar додано до alphabet-toolbar-view.fxml");
    }

    public void setOnLetterSelected(java.util.function.Consumer<Character> listener) {
        alphabetToolbar.setOnLetterSelected(listener);
    }

    public char getSelectedLetter() {
        return alphabetToolbar.getSelectedButton() != null
                ? alphabetToolbar.getSelectedButton().getLetter()
                : '*';
    }

    public void clearSelection() {
        alphabetToolbar.clearSelection();
    }
}