package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.core.ReaderEngineBuilder;
import com.myhomelibcorp.reader.core.registry.DefaultBookFormatRegistry;
import com.myhomelibcorp.reader.layout.TextLayoutEngine;
import com.myhomelibcorp.reader.format.fb2.Fb2Format;
import com.myhomelibcorp.reader.format.epub.EpubFormat;
import com.myhomelibcorp.reader.format.txt.TxtFormat;
import com.myhomelibcorp.reader.format.zip.ZipFormat;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.BorderPane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.function.Consumer;

/** Готовий JavaFX-компонент читалки. */
@Slf4j
public class ReaderView extends BorderPane {

    @Getter
    private final ReaderCanvas canvas;
    @Getter
    private final ReaderToolbar toolbar;
    @Getter
    private final ReaderEngine engine;
    @Getter
    private final DefaultBookFormatRegistry formatRegistry;
    @Getter
    private final JavaFxReaderRenderer renderer;

    private Consumer<ReaderSettings> onSettingsClick;
    private Consumer<ReaderSettings> onSettingsChanged;
    private Runnable onBookmarkClick;
    private Runnable onTocClick;
    private Runnable onSearchClick;
    private Runnable onBackClick;

    public ReaderView() {
        formatRegistry = new DefaultBookFormatRegistry();
        formatRegistry.register(new Fb2Format());
        formatRegistry.register(new EpubFormat());
        formatRegistry.register(new TxtFormat());
        formatRegistry.register(new ZipFormat());

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

        canvas = new ReaderCanvas(engine, renderer);
        toolbar = new ReaderToolbar(canvas);

        setTop(toolbar);
        setCenter(canvas);
        setupCallbacks();
    }

    private void setupCallbacks() {
        canvas.setOnPageChanged(toolbar::updateState);
        canvas.setOnPageNumberChanged(page -> toolbar.updateState());
        canvas.setOnCloseRequested(() -> {
            if (onBackClick != null) onBackClick.run();
            else closeBook();
        });
        canvas.setOnCenterTap(this::toggleToolbarVisibility);
        canvas.setOnSearchRequested(() -> {
            if (onSearchClick != null) onSearchClick.run();
        });

        toolbar.setOnSettingsClick(settings -> {
            if (onSettingsClick != null) onSettingsClick.accept(settings);
        });
        toolbar.setOnQuickSettingsChanged(settings -> {
            if (onSettingsChanged != null) onSettingsChanged.accept(settings);
        });
        toolbar.setOnBookmarkClick(() -> {
            if (onBookmarkClick != null) onBookmarkClick.run();
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
    }

    private void toggleToolbarVisibility() {
        boolean visible = !toolbar.isVisible();
        toolbar.setVisible(visible);
        toolbar.setManaged(visible);
        // Зміна висоти toolbar змінює viewport; layout оновиться після pulse.
        Platform.runLater(() -> {
            if (isBookOpen()) canvas.updateSize();
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

    public void setOnTocClick(Runnable listener) {
        onTocClick = listener;
    }

    public void setOnSearchClick(Runnable listener) {
        onSearchClick = listener;
    }

    public void setOnBackClick(Runnable listener) {
        onBackClick = listener;
    }

    public void openBook(BookSource source) throws IOException {
        engine.open(source);
        if (engine.getCurrentDocument() != null) {
            renderer.setResourceRepository(engine.getCurrentDocument().resources());
        }
        renderer.applySettings(engine.getSettings());
        toolbar.setVisible(engine.getSettings().showToolbar());
        toolbar.setManaged(engine.getSettings().showToolbar());
        canvas.setAutoScrollSpeed(engine.getSettings().scrollSpeed());
        if (engine.getSettings().autoScroll() && !canvas.isAutoScrollRunning()) {
            canvas.toggleAutoScroll();
        }
        canvas.updateSize();
        toolbar.updateState();
        Platform.runLater(canvas::requestFocus);
        log.info("📖 ReaderView opened: {}", source.name());
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
        canvas.applySettings(settings);
        toolbar.setVisible(settings.showToolbar());
        toolbar.setManaged(settings.showToolbar());
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
        canvas.dispose();
        renderer.setResourceRepository(null);
        log.info("🧹 ReaderView disposed");
    }
}
