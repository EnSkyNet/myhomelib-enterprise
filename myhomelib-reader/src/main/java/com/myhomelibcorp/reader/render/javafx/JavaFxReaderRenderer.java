package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.reader.api.ResourceRepository;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.model.TextRunLayout;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;
import com.myhomelibcorp.reader.render.api.RenderMetrics;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JavaFX Canvas renderer. У пам'яті тримає лише невеликий LRU зображень.
 * Rich-text FB2 малюється компактними runs, без WebView/HTML DOM.
 */
@Slf4j
public class JavaFxReaderRenderer implements ReaderRenderer {

    private static final int MAX_IMAGE_CACHE_ENTRIES = 8;

    private final Canvas canvas;
    private final GraphicsContext gc;
    private FontProvider fontProvider;
    private ResourceRepository resources;

    private RenderMetrics metrics = RenderMetrics.empty();
    private final Map<String, Font> fontCache = new LinkedHashMap<>();
    private final Map<String, Image> imageCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > MAX_IMAGE_CACHE_ENTRIES;
        }
    };

    public JavaFxReaderRenderer(Canvas canvas, FontProvider fontProvider) {
        if (canvas == null) {
            throw new IllegalArgumentException("canvas is required");
        }
        this.canvas = canvas;
        this.gc = canvas.getGraphicsContext2D();
        this.fontProvider = fontProvider != null ? fontProvider : new FontProvider("Georgia");
    }

    @Override
    public void renderPage(PageLayout page, ReaderTheme theme) {
        if (theme == null) {
            theme = ReaderTheme.fromName("light");
        }

        paintBackground(theme);
        if (page == null || page.isEmpty()) {
            return;
        }

        long started = System.currentTimeMillis();
        List<LineLayout> lines = page.getLines();
        for (LineLayout line : lines) {
            renderLine(line, theme);
        }

        long renderTime = System.currentTimeMillis() - started;
        metrics = metrics.withRenderTime(renderTime);
        if (renderTime > 50) {
            log.debug("⏱️ Canvas render {} ms / {} lines", renderTime, lines.size());
        }
    }

    private void paintBackground(ReaderTheme theme) {
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setFill(Color.web(theme.background()));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void renderLine(LineLayout line, ReaderTheme theme) {
        if (line == null || line.isEmpty()) {
            return;
        }

        String text = line.text();
        if (text.startsWith("[IMAGE:") && text.endsWith("]")) {
            renderImageMarker(text, line.x(), line.y(), Math.max(line.height(), line.fontSize() * 4f));
            return;
        }

        if (line.hasStyledRuns()) {
            for (TextRunLayout run : line.runs()) {
                renderRun(line, run, theme);
            }
        } else {
            renderPlainText(line.text(), line.x(), line.getBaselineY(), line.width(),
                    line.fontSize(), line.style(), line, theme);
        }
    }

    private void renderRun(LineLayout line, TextRunLayout run, ReaderTheme theme) {
        if (run == null || run.isEmpty()) {
            return;
        }

        TextStyle style = run.style() != null ? run.style() : line.style();
        float fontSize = Math.max(6f, run.fontSize());
        float x = line.x() + run.x();
        float baseline = line.getBaselineY();

        if (style == TextStyle.SUPERSCRIPT) {
            baseline -= line.fontSize() * 0.28f;
        } else if (style == TextStyle.SUBSCRIPT) {
            baseline += line.fontSize() * 0.16f;
        }

        if (style == TextStyle.CODE) {
            gc.setFill(Color.web(theme.codeBackground()));
            gc.fillRoundRect(x - 1, line.y() + 1, run.width() + 2,
                    Math.max(1, line.height() - 2), 3, 3);
        }

        renderPlainText(run.text(), x, baseline, run.width(), fontSize, style, line, theme);
    }

    private void renderPlainText(
            String text,
            float x,
            float baseline,
            float width,
            float fontSize,
            TextStyle style,
            LineLayout line,
            ReaderTheme theme
    ) {
        if (text == null || text.isEmpty()) {
            return;
        }

        TextStyle effective = style != null ? style : TextStyle.NORMAL;
        String fontKey = effective.name() + ':' + Math.round(fontSize * 10f);
        Font font = fontCache.computeIfAbsent(fontKey, k -> fontProvider.getFont(effective, fontSize));

        gc.setFont(font);
        gc.setFill(Color.web(effective == TextStyle.LINK ? theme.linkColor() : theme.foreground()));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        gc.fillText(text, x, baseline);

        if (effective == TextStyle.UNDERLINE || effective == TextStyle.LINK) {
            gc.setStroke(Color.web(effective == TextStyle.LINK ? theme.linkColor() : theme.foreground()));
            gc.strokeLine(x, baseline + 2, x + width, baseline + 2);
        }
        if (effective == TextStyle.STRIKETHROUGH) {
            gc.setStroke(Color.web(theme.foreground()));
            gc.strokeLine(x, baseline - fontSize * 0.30f,
                    x + width, baseline - fontSize * 0.30f);
        }
    }

    private void renderImageMarker(String marker, float x, float y, float requestedHeight) {
        int start = marker.indexOf(':') + 1;
        int end = marker.lastIndexOf(']');
        if (start <= 0 || end <= start) {
            return;
        }
        String imageId = marker.substring(start, end);
        Image image = imageCache.get(imageId);
        if (image == null) {
            image = loadImage(imageId);
        }

        if (image == null || image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            gc.setFill(Color.GRAY);
            gc.fillRect(x, y, 24, 24);
            return;
        }

        double maxWidth = Math.max(1, canvas.getWidth() - x - 20);
        double targetHeight = Math.min(requestedHeight, canvas.getHeight() * 0.45);
        double scale = Math.min(maxWidth / image.getWidth(), targetHeight / image.getHeight());
        scale = Math.min(1.0, Math.max(0.05, scale));
        gc.drawImage(image, x, y, image.getWidth() * scale, image.getHeight() * scale);
    }

    private Image loadImage(String imageId) {
        if (resources == null || imageId == null) {
            return null;
        }
        try {
            var streamOpt = resources.open(imageId);
            if (streamOpt.isEmpty()) {
                return null;
            }
            try (InputStream in = streamOpt.get()) {
                Image image = new Image(in);
                if (!image.isError()) {
                    imageCache.put(imageId, image);
                    return image;
                }
            }
        } catch (Exception e) {
            log.debug("Не вдалося завантажити image {}: {}", imageId, e.getMessage());
        }
        return null;
    }

    public void setResourceRepository(ResourceRepository resources) {
        this.resources = resources;
        clearImageCache();
    }

    public void applySettings(ReaderSettings settings) {
        if (settings == null) {
            return;
        }
        this.fontProvider = this.fontProvider.withFontFamily(settings.fontFamily());
        clearFontCache();
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

}
