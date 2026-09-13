package com.myhomelibcorp.reader.audio;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Minimal internal audiobook UI for MP3/M4B. */
public final class AudioReaderView extends BorderPane implements AutoCloseable {
    private final Function<String, String> text;
    private final Label title = new Label();
    private final Label time = new Label();
    private final Slider position = new Slider(0, 100, 0);
    private final Button playPause = new Button();
    private final ComboBox<Double> speed = new ComboBox<>();
    private final ComboBox<Integer> sleep = new ComboBox<>();
    private final ComboBox<ChapterChoice> chapters = new ComboBox<>();
    private final Timeline ticker;
    private AudioDocumentSession session;
    private boolean sliderChanging;
    private boolean updatingChapter;
    private Runnable onBack = () -> {};
    private Runnable onAddBookmark = () -> {};
    private Runnable onBookmarks = () -> {};
    private Consumer<AudioPosition> onPositionChanged = p -> {};
    private Consumer<String> onError = s -> {};

    public AudioReaderView(Function<String, String> text) {
        this.text = text == null ? key -> key : text;
        setPadding(new Insets(18));
        Button back = new Button(this.text.apply("ui.reader.audio.back"));
        back.setOnAction(e -> onBack.run());
        Button addBookmark = new Button(this.text.apply("ui.reader.audio.bookmark"));
        addBookmark.setOnAction(e -> onAddBookmark.run());
        Button bookmarks = new Button(this.text.apply("ui.reader.audio.bookmarks"));
        bookmarks.setOnAction(e -> onBookmarks.run());
        HBox top = new HBox(10, back, title, new Region(), addBookmark, bookmarks);
        HBox.setHgrow(top.getChildren().get(2), Priority.ALWAYS);
        top.setAlignment(Pos.CENTER_LEFT);
        setTop(top);

        playPause.setOnAction(e -> togglePlayback());
        speed.getItems().setAll(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0);
        speed.setValue(1.0);
        speed.setOnAction(e -> { if (session != null && speed.getValue() != null) try { session.setRate(speed.getValue()); } catch (Exception ex) { fail(ex); } });
        sleep.getItems().setAll(0, 10, 20, 30, 45, 60);
        sleep.setValue(0);
        sleep.setOnAction(e -> {
            if (session == null || sleep.getValue() == null) return;
            int minutes = sleep.getValue();
            session.setSleepTimer(minutes <= 0 ? java.time.Duration.ZERO : java.time.Duration.of(minutes, ChronoUnit.MINUTES));
        });
        chapters.setOnAction(e -> {
            if (updatingChapter || session == null || chapters.getValue() == null) return;
            ChapterChoice c = chapters.getValue();
            try { session.seek(new AudioPosition(c.chapter.startMillis(), c.trackIndex, c.chapterIndex)); notifyPosition(); }
            catch (IOException ex) { fail(ex); }
        });
        position.valueChangingProperty().addListener((o, oldV, changing) -> {
            sliderChanging = changing;
            if (!changing) seekFromSlider();
        });
        position.setOnMouseReleased(e -> seekFromSlider());

        HBox controls = new HBox(10, playPause, new Label(this.text.apply("ui.reader.audio.speed")), speed,
                new Label(this.text.apply("ui.reader.audio.sleep")), sleep,
                new Label(this.text.apply("ui.reader.audio.chapter")), chapters);
        controls.setAlignment(Pos.CENTER);
        VBox center = new VBox(16, time, position, controls);
        center.setAlignment(Pos.CENTER);
        center.setMaxWidth(900);
        StackPane wrapper = new StackPane(center);
        setCenter(wrapper);

        ticker = new Timeline(new KeyFrame(Duration.millis(500), e -> tick()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        updatePlayLabel();
    }

    public void openPrepared(AudioDocumentSession session, AudioPosition saved) {
        closeDocument();
        this.session = session;
        if (saved != null) try { session.seek(saved); } catch (IOException e) { fail(e); }
        title.setText(session.tracks().get(session.currentPosition().trackIndex()).title());
        rebuildChapters();
        ticker.play();
        tick();
    }

    public boolean isOpen() { return session != null; }
    public AudioPosition currentPosition() { return session == null ? AudioPosition.start() : session.currentPosition(); }
    public long totalDurationMillis() { return session == null ? 0L : session.totalDurationMillis(); }
    public double progressPercent() { return session == null ? 0.0 : session.progressPercent(); }
    public List<AudioTrack> tracks() { return session == null ? List.of() : session.tracks(); }
    public void goToPosition(AudioPosition target) { if (session != null) try { session.seek(target); notifyPosition(); } catch (IOException e) { fail(e); } }

    public void setOnBack(Runnable value) { onBack = value == null ? () -> {} : value; }
    public void setOnAddBookmark(Runnable value) { onAddBookmark = value == null ? () -> {} : value; }
    public void setOnBookmarks(Runnable value) { onBookmarks = value == null ? () -> {} : value; }
    public void setOnPositionChanged(Consumer<AudioPosition> value) { onPositionChanged = value == null ? p -> {} : value; }
    public void setOnError(Consumer<String> value) { onError = value == null ? s -> {} : value; }

    public void closeDocument() {
        ticker.stop();
        if (session != null) session.close();
        session = null;
        position.setValue(0);
        time.setText("");
        chapters.getItems().clear();
        updatePlayLabel();
    }

    @Override public void close() { closeDocument(); }

    private void togglePlayback() {
        if (session == null) return;
        try {
            if (session.isPlaying()) session.pause(); else session.play();
            notifyPosition();
        } catch (IOException e) { fail(e); }
        updatePlayLabel();
    }

    private void tick() {
        if (session == null) return;
        session.tickSleepTimer();
        AudioPosition p = session.currentPosition();
        if (!sliderChanging) position.setValue(session.progressPercent());
        title.setText(session.tracks().get(p.trackIndex()).title());
        time.setText(formatTime(session.absoluteMillis(p)) + " / " + formatTime(session.totalDurationMillis()));
        selectChapter(p);
        updatePlayLabel();
        onPositionChanged.accept(p);
    }

    private void seekFromSlider() {
        if (session == null || sliderChanging) return;
        double wanted = Math.max(0.0, Math.min(100.0, position.getValue()));
        long absolute = Math.round(session.totalDurationMillis() * wanted / 100.0);
        long cursor = 0L;
        List<AudioTrack> tracks = session.tracks();
        for (int i = 0; i < tracks.size(); i++) {
            long end = cursor + tracks.get(i).durationMillis();
            if (absolute <= end || i == tracks.size() - 1) {
                try { session.seek(new AudioPosition(Math.max(0L, absolute - cursor), i, 0)); notifyPosition(); }
                catch (IOException e) { fail(e); }
                return;
            }
            cursor = end;
        }
    }

    private void rebuildChapters() {
        chapters.getItems().clear();
        if (session == null) return;
        for (int t = 0; t < session.tracks().size(); t++) {
            List<AudioChapter> list = session.tracks().get(t).chapters();
            for (int c = 0; c < list.size(); c++) chapters.getItems().add(new ChapterChoice(t, c, list.get(c)));
        }
        selectChapter(session.currentPosition());
    }

    private void selectChapter(AudioPosition p) {
        chapters.getItems().stream().filter(c -> c.trackIndex == p.trackIndex() && c.chapterIndex == p.chapterIndex())
                .findFirst().ifPresent(choice -> {
                    if (choice.equals(chapters.getValue())) return;
                    updatingChapter = true;
                    try { chapters.setValue(choice); }
                    finally { updatingChapter = false; }
                });
    }

    private void updatePlayLabel() { playPause.setText(session != null && session.isPlaying() ? text.apply("ui.reader.audio.pause") : text.apply("ui.reader.audio.play")); }
    private void notifyPosition() { if (session != null) onPositionChanged.accept(session.currentPosition()); }
    private void fail(Exception e) { onError.accept(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
    private static String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L); long h = seconds / 3600; long m = (seconds % 3600) / 60; long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
    private record ChapterChoice(int trackIndex, int chapterIndex, AudioChapter chapter) {
        @Override public String toString() { return chapter.title(); }
    }
}
