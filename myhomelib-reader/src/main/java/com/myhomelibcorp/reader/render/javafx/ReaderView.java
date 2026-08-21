package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.core.ReaderEngineBuilder;
import com.myhomelibcorp.reader.core.registry.DefaultBookFormatRegistry;
import javafx.animation.AnimationTimer;
import javafx.scene.layout.BorderPane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.function.Consumer;

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
    private Runnable onBookmarkClick;
    private Runnable onTocClick;
    private Runnable onSearchClick;
    private Runnable onBackClick;

    public ReaderView() {
        this.formatRegistry = new DefaultBookFormatRegistry();

        FontProvider fontProvider = new FontProvider("Georgia");
        javafx.scene.canvas.Canvas canvasNode = new javafx.scene.canvas.Canvas();
        this.renderer = new JavaFxReaderRenderer(canvasNode, fontProvider);

        ReaderEngineBuilder builder = new ReaderEngineBuilder()
                .formatRegistry(formatRegistry)
                .renderer(renderer);

        this.engine = builder.build();

        this.canvas = new ReaderCanvas(engine, renderer);
        this.toolbar = new ReaderToolbar(canvas);

        setTop(toolbar);
        setCenter(canvas);

        setupCallbacks();

        AnimationTimer timer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(long now) {
                if (now - lastUpdate > 500_000_000) {
                    lastUpdate = now;
                    toolbar.updateState();
                }
            }
        };
        timer.start();

        log.info("✅ ReaderView створено");
    }

    private void setupCallbacks() {
        canvas.setOnPageChanged(toolbar::updateState);
        canvas.setOnPositionChanged(pos -> toolbar.updateState());
        canvas.setOnPageNumberChanged(page -> toolbar.updateState());

        toolbar.setOnSettingsClick(settings -> {
            if (onSettingsClick != null) {
                onSettingsClick.accept(settings);
            }
        });

        toolbar.setOnBookmarkClick(() -> {
            if (onBookmarkClick != null) {
                onBookmarkClick.run();
            }
        });

        toolbar.setOnTocClick(() -> {
            if (onTocClick != null) {
                onTocClick.run();
            }
        });

        toolbar.setOnSearchClick(() -> {
            if (onSearchClick != null) {
                onSearchClick.run();
            }
        });

        toolbar.setOnBackClick(() -> {
            if (onBackClick != null) {
                onBackClick.run();
            }
        });
    }

    // ==================== КОЛБЕКИ ====================

    public void setOnSettingsClick(Consumer<ReaderSettings> listener) {
        this.onSettingsClick = listener;
        toolbar.setOnSettingsClick(listener);
    }

    public void setOnBookmarkClick(Runnable listener) {
        this.onBookmarkClick = listener;
        toolbar.setOnBookmarkClick(listener);
    }

    public void setOnTocClick(Runnable listener) {
        this.onTocClick = listener;
        toolbar.setOnTocClick(listener);
    }

    public void setOnSearchClick(Runnable listener) {
        this.onSearchClick = listener;
        toolbar.setOnSearchClick(listener);
    }

    public void setOnBackClick(Runnable listener) {
        this.onBackClick = listener;
        toolbar.setOnBackClick(listener);
    }

    // ==================== ОСНОВНІ МЕТОДИ ====================

    public void openBook(BookSource source) throws IOException {
        engine.open(source);

        // Кешуємо зображення для рендерингу
        if (engine.getCurrentDocument() != null) {
            var resources = engine.getCurrentDocument().resources();
            for (String id : resources.getAllIds()) {
                var info = resources.getInfo(id).orElse(null);
                if (info != null && info.isImage()) {
                    var data = resources.open(id).orElse(null);
                    if (data != null) {
                        try {
                            byte[] bytes = data.readAllBytes();
                            renderer.cacheImage(id, bytes);
                        } catch (Exception e) {
                            log.warn("Не вдалося завантажити зображення {}: {}", id, e.getMessage());
                        }
                    }
                }
            }
        }

        canvas.render();
        toolbar.updateState();
        log.info("📖 Книгу відкрито в ReaderView");
    }

    public void closeBook() {
        canvas.closeBook();
        toolbar.updateState();
        renderer.clearImageCache();
    }

    public boolean isBookOpen() {
        return canvas.isBookOpen();
    }

    public void applySettings(ReaderSettings settings) {
        canvas.applySettings(settings);
        toolbar.updateState();
    }

    public void goToPercent(double percent) {
        canvas.goToPercent(percent);
    }

    public void goToPosition(ReaderPosition position) {
        canvas.goToPosition(position);
    }

    public void nextPage() {
        canvas.nextPage();
    }

    public void previousPage() {
        canvas.previousPage();
    }

    public double getProgressPercent() {
        return canvas.getProgressPercent();
    }

    public String getCurrentChapterTitle() {
        return canvas.getCurrentChapterTitle();
    }

    public ReaderPosition getCurrentPosition() {
        return canvas.getCurrentPosition();
    }

    public String getCacheStats() {
        return canvas.getCacheStats();
    }

    public void registerFormat(com.myhomelibcorp.reader.api.BookFormat format) {
        formatRegistry.register(format);
        log.info("📚 Формат зареєстровано: {}", format.displayName());
    }

    public void dispose() {
        renderer.clearImageCache();
        canvas.dispose();
        log.info("🧹 ReaderView знищено");
    }
}