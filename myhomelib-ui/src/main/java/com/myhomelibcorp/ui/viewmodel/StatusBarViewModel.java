package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import javafx.beans.property.*;

/**
 * Status-bar state with a dedicated background-operation layer.
 *
 * <p>Normal UI actions keep writing their transient status through the legacy setters. A coordinated
 * background operation may temporarily take visual priority without destroying that foreground state;
 * when the background operation ends, the previous status/progress becomes visible again.</p>
 */
public class StatusBarViewModel {

    private static final String DEFAULT_STATUS = "Готово до роботи";

    private final StringProperty statusText = new SimpleStringProperty(DEFAULT_STATUS);
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final BooleanProperty progressVisible = new SimpleBooleanProperty(false);
    private final ObjectProperty<LibraryStatistics> statistics = new SimpleObjectProperty<>();

    private String foregroundStatus = DEFAULT_STATUS;
    private double foregroundProgress;
    private boolean foregroundProgressVisible;

    private boolean backgroundOperationActive;
    private String backgroundStatus = "";
    private double backgroundProgress = -1.0;
    private boolean backgroundProgressVisible;

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
        foregroundStatus = text == null || text.isBlank() ? DEFAULT_STATUS : text;
        refreshEffectiveState();
    }

    public void setProgress(double value) {
        foregroundProgress = value;
        refreshEffectiveState();
    }

    public void setProgressVisible(boolean visible) {
        foregroundProgressVisible = visible;
        refreshEffectiveState();
    }

    /** Shows a background operation with higher display priority than transient foreground messages. */
    public void setBackgroundOperation(String text, double progressValue, boolean showProgress) {
        backgroundOperationActive = true;
        backgroundStatus = text == null || text.isBlank() ? "Виконується фонова операція…" : text;
        backgroundProgress = progressValue;
        backgroundProgressVisible = showProgress;
        refreshEffectiveState();
    }

    /** Restores the foreground status/progress that were active before the background operation. */
    public void clearBackgroundOperation() {
        backgroundOperationActive = false;
        backgroundStatus = "";
        backgroundProgress = -1.0;
        backgroundProgressVisible = false;
        refreshEffectiveState();
    }

    public boolean isBackgroundOperationActive() {
        return backgroundOperationActive;
    }

    public void setStatistics(LibraryStatistics stats) {
        statistics.set(stats);
    }

    private void refreshEffectiveState() {
        if (backgroundOperationActive) {
            statusText.set(backgroundStatus);
            progress.set(backgroundProgress);
            progressVisible.set(backgroundProgressVisible);
        } else {
            statusText.set(foregroundStatus);
            progress.set(foregroundProgress);
            progressVisible.set(foregroundProgressVisible);
        }
    }
}
