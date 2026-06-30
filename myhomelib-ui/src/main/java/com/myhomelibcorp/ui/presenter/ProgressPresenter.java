package com.myhomelibcorp.ui.presenter;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.ProgressBar;
import org.springframework.stereotype.Component;

@Component
public class ProgressPresenter {

    private ProgressBar progressBar;
    private final DoubleProperty progress = new SimpleDoubleProperty(0);

    public void bind(ProgressBar progressBar) {
        this.progressBar = progressBar;
        this.progressBar.progressProperty().bind(progress);
        this.progressBar.setVisible(false);
    }

    public void showProgress(boolean show) {
        if (progressBar != null) {
            progressBar.setVisible(show);
            if (!show) progress.set(0);
        }
    }

    public void setProgress(double value) {
        progress.set(value);
    }

    public void hideProgress() {
        showProgress(false);
    }
}