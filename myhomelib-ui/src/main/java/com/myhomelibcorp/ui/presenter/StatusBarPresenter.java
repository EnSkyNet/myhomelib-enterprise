package com.myhomelibcorp.ui.presenter;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Label;
import org.springframework.stereotype.Component;

@Component
public class StatusBarPresenter {

    private Label statusLabel;
    private final StringProperty statusText = new SimpleStringProperty("Готово до роботи");

    public void bind(Label statusLabel) {
        this.statusLabel = statusLabel;
        this.statusLabel.textProperty().bind(statusText);
    }

    public void setStatus(String status) {
        statusText.set(status);
    }

    public String getStatus() {
        return statusText.get();
    }
}