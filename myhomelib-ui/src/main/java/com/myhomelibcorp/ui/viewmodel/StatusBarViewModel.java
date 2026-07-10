package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import javafx.beans.property.*;

public class StatusBarViewModel {

    private final StringProperty statusText = new SimpleStringProperty("Готово до роботи");
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final BooleanProperty progressVisible = new SimpleBooleanProperty(false);
    private final ObjectProperty<LibraryStatistics> statistics = new SimpleObjectProperty<>();

    public StringProperty statusTextProperty() {
        return statusText;
    }

    public DoubleProperty progressProperty() {
        return progress;
    }

    public BooleanProperty progressVisibleProperty() {
        return progressVisible;
    }

    public ObjectProperty<LibraryStatistics> statisticsProperty() {
        return statistics;
    }

    public void setStatusText(String text) {
        statusText.set(text);
    }

    public void setProgress(double value) {
        progress.set(value);
    }

    public void setProgressVisible(boolean visible) {
        progressVisible.set(visible);
    }

    public void setStatistics(LibraryStatistics stats) {
        statistics.set(stats);
    }
}