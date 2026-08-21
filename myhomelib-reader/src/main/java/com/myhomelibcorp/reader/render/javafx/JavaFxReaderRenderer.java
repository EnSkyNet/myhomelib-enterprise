package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.render.api.RenderMetrics;
import com.myhomelibcorp.reader.render.api.RenderSurface;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class JavaFxReaderRenderer implements ReaderRenderer {

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final FontProvider fontProvider;

    private RenderMetrics metrics = RenderMetrics.empty();
    private long lastRenderStart = 0;
    private final Map<String, Font> fontCache = new HashMap<>();
    private final Map<String, Image> imageCache = new HashMap<>();

    public JavaFxReaderRenderer(Canvas canvas, FontProvider fontProvider) {
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
        this.fontProvider = fontProvider;
    }

    @Override
    public void renderPage(PageLayout page, RenderSurface surface, ReaderTheme theme) {
        if (page == null || page.isEmpty()) {
            clear();
            return;
        }

        lastRenderStart = System.currentTimeMillis();

        clear();

        gc.setFill(Color.web(theme.background()));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        List<LineLayout> lines = page.getLines();
        for (LineLayout line : lines) {
            renderLine(line, theme);
        }

        long renderTime = System.currentTimeMillis() - lastRenderStart;
        metrics = metrics.withRenderTime(renderTime);

        if (renderTime > 50) {
            log.debug("⏱️ Рендеринг сторінки зайняв {} мс, {} рядків",
                    renderTime, lines.size());
        }
    }

    private void renderLine(LineLayout line, ReaderTheme theme) {
        if (line == null || line.isEmpty()) {
            return;
        }

        String text = line.text();
        float x = line.x();
        float y = line.getBaselineY();

        // Перевіряємо, чи це маркер зображення
        if (text != null && text.startsWith("[IMAGE:") && text.endsWith("]")) {
            renderImageMarker(text, x, y, line.height());
            return;
        }

        Color color = Color.web(theme.foreground());
        TextStyle style = line.style() != null ? line.style() : TextStyle.NORMAL;

        String fontKey = style.name() + "_" + (int) line.height();
        Font font = fontCache.get(fontKey);
        if (font == null) {
            font = fontProvider.getFont(style, line.height());
            fontCache.put(fontKey, font);
        }

        gc.setFont(font);
        gc.setFill(color);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(javafx.geometry.VPos.BASELINE);

        gc.fillText(text, x, y);

        if (style == TextStyle.UNDERLINE) {
            gc.strokeLine(x, y + 2, x + line.width(), y + 2);
        }

        if (style == TextStyle.STRIKETHROUGH) {
            gc.strokeLine(x, y - line.height() * 0.3f, x + line.width(), y - line.height() * 0.3f);
        }
    }

    /**
     * Рендерить маркер зображення.
     * Формат: [IMAGE:id]
     */
    private void renderImageMarker(String marker, float x, float y, float height) {
        // Видобуваємо ID зображення
        int start = marker.indexOf(':') + 1;
        int end = marker.indexOf(']');
        if (start < 0 || end < 0 || start >= end) {
            return;
        }
        String imageId = marker.substring(start, end);

        // Шукаємо зображення в кеші
        Image image = imageCache.get(imageId);
        if (image == null) {
            // TODO: Завантажити зображення з ResourceRepository
            // Поки що малюємо заглушку
            gc.setFill(Color.GRAY);
            gc.fillRect(x, y, height, height);
            gc.setFill(Color.WHITE);
            gc.fillText("🖼️", x + 2, y + height - 2);
            return;
        }

        // Малюємо зображення
        double imageWidth = Math.min(height * 2, image.getWidth());
        double imageHeight = imageWidth * (image.getHeight() / image.getWidth());
        gc.drawImage(image, x, y, imageWidth, imageHeight);
    }

    /**
     * Додає зображення в кеш для рендерингу.
     */
    public void cacheImage(String id, byte[] data) {
        if (id == null || data == null || data.length == 0) {
            return;
        }
        try {
            Image image = new Image(new ByteArrayInputStream(data));
            imageCache.put(id, image);
            log.debug("🖼️ Зображення кешовано: {}", id);
        } catch (Exception e) {
            log.warn("Не вдалося закешувати зображення {}: {}", id, e.getMessage());
        }
    }

    @Override
    public RenderMetrics getMetrics() {
        return metrics;
    }

    @Override
    public void clear() {
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    public void clearFontCache() {
        fontCache.clear();
    }

    public void clearImageCache() {
        imageCache.clear();
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public GraphicsContext getGraphicsContext() {
        return gc;
    }

    @Override
    public boolean isReady() {
        return canvas.getWidth() > 0 && canvas.getHeight() > 0;
    }
}