package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSelection;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.core.ReaderEngineBuilder;
import com.myhomelibcorp.reader.core.ReaderEngine.PreparedBook;
import com.myhomelibcorp.reader.core.registry.DefaultBookFormatRegistry;
import com.myhomelibcorp.reader.layout.TextLayoutEngine;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.BorderPane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Готовий JavaFX-компонент читалки. */
@Slf4j
public class ReaderView extends BorderPane {

    @Getter
    private final ReaderCanvas canvas;
    @Getter
    private final ReaderToolbar toolbar;
    @Getter
    private final ReaderStatusBar statusBar;
    private boolean reducedMotion;
    @Getter
    private final ReaderEngine engine;
    @Getter
    private final DefaultBookFormatRegistry formatRegistry;
    @Getter
    private final JavaFxReaderRenderer renderer;

    private Consumer<ReaderSettings> onSettingsClick;
    private Consumer<ReaderSettings> onSettingsChanged;
    private Runnable onBookmarkClick;
    private Runnable onBookmarksClick;
    private Runnable onTocClick;
    private Runnable onSearchClick;
    private Runnable onBackClick;
    private Runnable onTtsStartClick;
    private Runnable onTtsPauseClick;
    private Runnable onTtsStopClick;

    public ReaderView() {
        this(key -> key);
    }

    public ReaderView(Function<String, String> text) {
        formatRegistry = DefaultBookFormatRegistry.standard();

        // Один Canvas на весь render pipeline.
        Canvas canvasNode = new Canvas();
        ReaderSettings initialSettings = ReaderSettings.defaultSettings();
        renderer = new JavaFxReaderRenderer(canvasNode, new FontProvider(initialSettings.fontFamily()));
        engine = new ReaderEngineBuilder()
                .formatRegistry(formatRegistry)
                .settings(initialSettings)
                .renderer(renderer)
                .withLayoutEngine(new TextLayoutEngine(
                        new JavaFxFontMetricsProvider(initialSettings), initialSettings))
                .build();

        canvas = new ReaderCanvas(engine, renderer, text);
        toolbar = new ReaderToolbar(canvas, text);
        statusBar = new ReaderStatusBar(canvas, text);

        setTop(toolbar);
        setCenter(canvas);
        setBottom(statusBar);
        setupCallbacks();
    }

    private void setupCallbacks() {
        canvas.setOnPageChanged(() -> { toolbar.updateState(); statusBar.updateState(); });
        canvas.setOnPageNumberChanged(page -> { toolbar.updateState(); statusBar.updateState(); });
        canvas.setOnCloseRequested(() -> {
            if (onBackClick != null) onBackClick.run();
            else closeBook();
        });
        canvas.setOnCenterTap(this::toggleToolbarVisibility);
        canvas.setOnToggleToolbarRequested(this::toggleToolbarVisibility);
        canvas.setOnSearchRequested(() -> {
            if (onSearchClick != null) onSearchClick.run();
        });

        toolbar.setOnSettingsClick(settings -> {
            if (onSettingsClick != null) onSettingsClick.accept(settings);
        });
        canvas.setOnSettingsChanged(settings -> {
            statusBar.applySettings(settings);
            toolbar.updateState();
            if (onSettingsChanged != null) onSettingsChanged.accept(settings);
        });
        toolbar.setOnBookmarkClick(() -> {
            if (onBookmarkClick != null) onBookmarkClick.run();
        });
        toolbar.setOnBookmarksClick(() -> {
            if (onBookmarksClick != null) onBookmarksClick.run();
        });
        toolbar.setOnTocClick(() -> {
            if (onTocClick != null) onTocClick.run();
        });
        toolbar.setOnSearchClick(() -> {
            if (onSearchClick != null) onSearchClick.run();
        });
        toolbar.setOnBackClick(() -> {
            if (onBackClick != null) onBackClick.run();
        });
        toolbar.setOnTtsStartClick(() -> { if (onTtsStartClick != null) onTtsStartClick.run(); });
        toolbar.setOnTtsPauseClick(() -> { if (onTtsPauseClick != null) onTtsPauseClick.run(); });
        toolbar.setOnTtsStopClick(() -> { if (onTtsStopClick != null) onTtsStopClick.run(); });
    }

    private void toggleToolbarVisibility() {
        ReaderPosition anchor = isBookOpen() ? canvas.getCurrentPosition() : null;
        boolean visible = !toolbar.isVisible();
        toolbar.setVisible(visible);
        toolbar.setManaged(visible);

        // Changing the toolbar height changes the pagination viewport. Re-layout on
        // the next JavaFX pulse and restore the exact text anchor so showing the
        // toolbar does not make the current page jump or render with stale geometry.
        Platform.runLater(() -> {
            applyCss();
            // managed changes alter BorderPane's top/center geometry. requestLayout() alone
            // may leave the old canvas height until a later pulse (visible on Windows when
            // the toolbar is restored). Layout this ReaderView now, then render against the
            // final viewport and restore the exact text anchor.
            requestLayout();
            layout();
            if (isBookOpen()) {
                canvas.updateSize();
                if (anchor != null) canvas.goToPosition(anchor);
            }
            canvas.requestFocus();
        });
    }

    public void setOnSettingsClick(Consumer<ReaderSettings> listener) {
        onSettingsClick = listener;
    }

    /** Called after toolbar shortcuts change persistent reader preferences. */
    public void setOnSettingsChanged(Consumer<ReaderSettings> listener) {
        onSettingsChanged = listener;
    }

    public void setOnBookmarkClick(Runnable listener) {
        onBookmarkClick = listener;
    }

    public void setOnBookmarksClick(Runnable listener) {
        onBookmarksClick = listener;
    }

    public void setOnTocClick(Runnable listener) {
        onTocClick = listener;
    }

    public void setOnSearchClick(Runnable listener) {
        onSearchClick = listener;
    }

    public void setOnBackClick(Runnable listener) {
        onBackClick = listener;
    }

    public void setOnTtsStartClick(Runnable listener) { onTtsStartClick = listener; }
    public void setOnTtsPauseClick(Runnable listener) { onTtsPauseClick = listener; }
    public void setOnTtsStopClick(Runnable listener) { onTtsStopClick = listener; }
    public void updateTtsState(boolean active, boolean paused) { toolbar.updateTtsState(active, paused); }

    public void setOnToggleLeftSidebarClick(Runnable listener) {
        toolbar.setOnToggleLeftSidebarClick(listener);
    }

    public void setOnToggleRightSidebarClick(Runnable listener) {
        toolbar.setOnToggleRightSidebarClick(listener);
    }

    public void setOnHighlightRequested(Consumer<ReaderSelection> listener) {
        canvas.setOnHighlightRequested(listener);
    }

    public void setOnNoteRequested(Consumer<ReaderSelection> listener) {
        canvas.setOnNoteRequested(listener);
    }

    public void setOnDictionaryRequested(Consumer<ReaderSelection> listener) {
        canvas.setOnDictionaryRequested(listener);
    }

    public void setOnTranslationRequested(Consumer<ReaderSelection> listener) {
        canvas.setOnTranslationRequested(listener);
    }

    public void setOnSelectionChanged(Consumer<Optional<ReaderSelection>> listener) {
        canvas.setOnSelectionChanged(listener);
    }

    public Optional<ReaderSelection> getSelection() {
        return canvas.getSelection();
    }

    public void clearTextSelection() {
        canvas.clearTextSelection();
    }

    public void setAnnotationOverlays(List<ReaderAnnotationOverlay> overlays) {
        canvas.setAnnotationOverlays(overlays);
    }

    public void setSpeechHighlight(long startOffset, long endOffset) {
        canvas.setSpeechHighlight(startOffset, endOffset);
    }

    public void clearSpeechHighlight() {
        canvas.clearSpeechHighlight();
    }

    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        canvas.setReducedMotion(reducedMotion);
        toolbar.updateState();
    }

    public boolean isReducedMotion() {
        return reducedMotion;
    }

    public void openBook(BookSource source) throws IOException {
        openPrepared(engine.prepare(source), null);
    }

    /**
     * Attaches a document that was parsed off the JavaFX thread. Initial position
     * is applied before the first layout so large books are not paginated twice.
     */
    public void openPrepared(PreparedBook prepared, ReaderPosition initialPosition) throws IOException {
        engine.openPrepared(prepared, initialPosition);
        if (reducedMotion && engine.getSettings().autoScroll()) {
            engine.applySettings(engine.getSettings().withAutoScroll(false));
        }
        if (engine.getCurrentDocument() != null) {
            renderer.setResourceRepository(engine.getCurrentDocument().resources());
        }
        renderer.applySettings(engine.getSettings());
        toolbar.setVisible(engine.getSettings().showToolbar());
        toolbar.setManaged(engine.getSettings().showToolbar());
        statusBar.applySettings(engine.getSettings());
        canvas.setAutoScrollSpeed(engine.getSettings().scrollSpeed());
        if (engine.getSettings().autoScroll() && !canvas.isAutoScrollRunning()) {
            canvas.toggleAutoScroll();
        }
        canvas.updateSize();
        toolbar.updateState();
        Platform.runLater(canvas::requestFocus);
        log.info("📖 ReaderView opened: {}", prepared.sourceName());
    }

    public void closeBook() {
        canvas.closeBook();
        toolbar.updateState();
    }

    public boolean isBookOpen() {
        return canvas.isBookOpen();
    }

    public void applySettings(ReaderSettings settings) {
        if (settings == null) return;
        if (reducedMotion && settings.autoScroll()) settings = settings.withAutoScroll(false);
        canvas.applySettings(settings);
        toolbar.setVisible(settings.showToolbar());
        toolbar.setManaged(settings.showToolbar());
        statusBar.applySettings(settings);
        canvas.setAutoScrollSpeed(settings.scrollSpeed());
        if (settings.autoScroll() && isBookOpen() && !canvas.isAutoScrollRunning()) {
            canvas.toggleAutoScroll();
        } else if (!settings.autoScroll() && canvas.isAutoScrollRunning()) {
            canvas.toggleAutoScroll();
        }
        toolbar.updateState();
    }

    public void goToPercent(double percent) { canvas.goToPercent(percent); }
    public void goToPosition(ReaderPosition position) { canvas.goToPosition(position); }
    public void nextPage() { canvas.nextPage(); }
    public void previousPage() { canvas.previousPage(); }
    public double getProgressPercent() { return canvas.getProgressPercent(); }
    public String getCurrentChapterTitle() { return canvas.getCurrentChapterTitle(); }
    public ReaderPosition getCurrentPosition() { return canvas.getCurrentPosition(); }
    public String getCacheStats() { return canvas.getCacheStats(); }

    public void registerFormat(BookFormat format) {
        formatRegistry.register(format);
    }

    public void dispose() {
        statusBar.dispose();
        canvas.dispose();
        renderer.setResourceRepository(null);
        log.info("🧹 ReaderView disposed");
    }
}
