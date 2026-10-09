package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.ui.service.UiText;
import javafx.beans.property.*;

/**
 * Status-bar state with a dedicated background-operation layer.
 *
 * <p>Normal UI actions keep writing their transient status through the legacy setters. A coordinated
 * background operation may temporarily take visual priority without destroying that foreground state;
 * when the background operation ends, the previous status/progress becomes visible again.</p>
 */
public class StatusBarViewModel {

    private static final String DEFAULT_STATUS_KEY = "ui.fxml.statusbar.ready";

    private final StringProperty statusText = new SimpleStringProperty(UiText.text(DEFAULT_STATUS_KEY));
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final BooleanProperty progressVisible = new SimpleBooleanProperty(false);
    private final ObjectProperty<LibraryStatistics> statistics = new SimpleObjectProperty<>();

    private String foregroundStatus = UiText.text(DEFAULT_STATUS_KEY);
    private double foregroundProgress;
    private boolean foregroundProgressVisible;
    private long foregroundRevision;
    private boolean foregroundRestoreAfterBackground = true;

    private boolean backgroundOperationActive;
    private long backgroundStartForegroundRevision;
    private boolean backgroundStartRestoreForeground = true;
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
        setForegroundStatus(text, true);
    }

    /**
     * Shows a transient foreground message that must not reappear after a later background operation.
     *
     * <p>This is intended for terminal notifications such as a completed book download. If the user
     * sees that message and a different coordinated operation later takes over the status bar, clearing
     * that background operation returns to Ready instead of resurrecting the old completion message.
     * A foreground status written while the background operation is active is still preserved normally.</p>
     */
    public void setTransientStatusText(String text) {
        setForegroundStatus(text, false);
    }

    private void setForegroundStatus(String text, boolean restoreAfterBackground) {
        foregroundStatus = text == null || text.isBlank() ? UiText.text(DEFAULT_STATUS_KEY) : text;
        foregroundRestoreAfterBackground = restoreAfterBackground;
        foregroundRevision++;
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
        if (!backgroundOperationActive) {
            backgroundStartForegroundRevision = foregroundRevision;
            backgroundStartRestoreForeground = foregroundRestoreAfterBackground;
        }
        backgroundOperationActive = true;
        backgroundStatus = text == null || text.isBlank() ? UiText.text("ui.java.status_bar_view_model.7d12d8cbba") : text;
        backgroundProgress = progressValue;
        backgroundProgressVisible = showProgress;
        refreshEffectiveState();
    }

    /** Restores the foreground status/progress that were active before the background operation. */
    public void clearBackgroundOperation() {
        if (!backgroundOperationActive) return;

        boolean foregroundChangedWhileHidden = foregroundRevision != backgroundStartForegroundRevision;
        if (!foregroundChangedWhileHidden && !backgroundStartRestoreForeground) {
            foregroundStatus = UiText.text(DEFAULT_STATUS_KEY);
            foregroundProgress = 0.0;
            foregroundProgressVisible = false;
            foregroundRestoreAfterBackground = true;
            foregroundRevision++;
        }

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
