package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderSettings;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Lightweight reader status line controlled by ReaderSettings. */
public final class ReaderStatusBar extends HBox {
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final ReaderCanvas canvas;
    private final Label chapter = new Label();
    private final Label page = new Label();
    private final Label progressText = new Label();
    private final Label clock = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final Timeline clockTimer;
    private ReaderSettings settings = ReaderSettings.defaultSettings();

    public ReaderStatusBar(ReaderCanvas canvas) {
        this.canvas = canvas;
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);
        setPadding(new Insets(3, 10, 3, 10));
        setStyle("-fx-background-color: rgba(128,128,128,0.10); -fx-border-color: rgba(128,128,128,0.25); -fx-border-width: 1 0 0 0;");
        progress.setPrefWidth(120);
        progress.setMinWidth(70);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(chapter, spacer, page, progress, progressText, clock);
        clockTimer = new Timeline(new KeyFrame(Duration.seconds(30), ignored -> updateClock()));
        clockTimer.setCycleCount(Animation.INDEFINITE);
        applySettings(settings);
    }

    public void applySettings(ReaderSettings settings) {
        this.settings = settings != null ? settings : ReaderSettings.defaultSettings();
        setVisible(this.settings.showStatusBar());
        setManaged(this.settings.showStatusBar());
        setNodeVisible(chapter, this.settings.showStatusChapter());
        setNodeVisible(page, this.settings.showStatusPage());
        setNodeVisible(progress, this.settings.showStatusProgress());
        setNodeVisible(progressText, this.settings.showStatusProgress());
        setNodeVisible(clock, this.settings.showStatusClock());
        if (this.settings.showStatusBar() && this.settings.showStatusClock()) {
            updateClock();
            if (clockTimer.getStatus() != Animation.Status.RUNNING) clockTimer.play();
        } else {
            clockTimer.stop();
        }
        updateState();
    }

    private void setNodeVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    public void updateState() {
        if (!canvas.isBookOpen()) {
            chapter.setText("");
            page.setText("");
            progress.setProgress(0);
            progressText.setText("0%");
            updateClock();
            return;
        }
        String title = canvas.getCurrentChapterTitle();
        chapter.setText(title == null || title.isBlank() ? "Розділ 1" : title);
        if (settings.showStatusPage()) {
            int currentPage = canvas.getCurrentPageNumber();
            int totalPages = canvas.getTotalPages();
            page.setText((currentPage > 0 ? Integer.toString(currentPage) : "…") + "/"
                    + (totalPages > 0 ? Integer.toString(totalPages) : "…") + " стор.");
        } else {
            page.setText("");
        }
        double pct = canvas.getProgressPercent();
        progress.setProgress(Math.max(0, Math.min(1, pct / 100.0)));
        progressText.setText(String.format("%.0f%%", pct));
        updateClock();
    }

    private void updateClock() { clock.setText(LocalTime.now().format(CLOCK_FORMAT)); }

    public void dispose() { clockTimer.stop(); }
}
