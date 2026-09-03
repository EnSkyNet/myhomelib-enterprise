package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderElementStyle;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderStyleSheet;
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
    private static final long MAX_IMAGE_CACHE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_FONT_CACHE_ENTRIES = 96;
    private static final double MAX_DECODE_DIMENSION = 3072.0;

    private final Canvas canvas;
    private final GraphicsContext gc;
    private FontProvider fontProvider;
    private ResourceRepository resources;
    private ReaderStyleSheet styleSheet = ReaderStyleSheet.defaults();

    private RenderMetrics metrics = RenderMetrics.empty();
    private final Map<String, Font> fontCache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Font> eldest) {
            return size() > MAX_FONT_CACHE_ENTRIES;
        }
    };
    private final Map<String, Image> imageCache = new LinkedHashMap<>(16, 0.75f, true);
    private long imageCacheBytes;

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
            renderLine(line, theme, 0.0, canvas.getWidth());
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

    /** Renders two independently paginated pages on the same Canvas. */
    public void renderSpread(PageLayout left, PageLayout right, ReaderTheme theme, double rightOffset, double pageWidth) {
        ReaderTheme effectiveTheme = theme != null ? theme : ReaderTheme.fromName("light");
        paintBackground(effectiveTheme);
        long started = System.currentTimeMillis();
        int lines = 0;
        if (left != null) {
            for (LineLayout line : left.getLines()) {
                renderLine(line, effectiveTheme, 0.0, pageWidth);
                lines++;
            }
        }
        if (right != null && !right.isEmpty()) {
            for (LineLayout line : right.getLines()) {
                renderLine(line, effectiveTheme, rightOffset, pageWidth);
                lines++;
            }
        }
        gc.setStroke(Color.web(effectiveTheme.foreground(), 0.16));
        double gutterCenter = rightOffset - Math.max(1.0, (rightOffset - pageWidth) / 2.0);
        gc.strokeLine(gutterCenter, 12, gutterCenter, Math.max(12, canvas.getHeight() - 12));
        long renderTime = System.currentTimeMillis() - started;
        metrics = metrics.withRenderTime(renderTime);
        if (renderTime > 50) log.debug("⏱️ Spread render {} ms / {} lines", renderTime, lines);
    }

    private void renderLine(LineLayout line, ReaderTheme theme, double xOffset, double pageWidth) {
        if (line == null || line.isEmpty()) {
            return;
        }

        String text = line.text();
        if (text.startsWith("[IMAGE:") && text.endsWith("]")) {
            renderImageMarker(text, line.x() + xOffset, line.y(), Math.max(line.height(), line.fontSize() * 4f), xOffset + pageWidth);
            return;
        }

        if (line.style() == TextStyle.QUOTE || line.style() == TextStyle.CITE) {
            gc.setFill(Color.web(theme.quoteBackground()));
            gc.fillRoundRect(line.x() + xOffset - 6, line.y(), Math.min(pageWidth, line.width() + 12), line.height(), 4, 4);
            gc.setStroke(Color.web(theme.quoteBorder()));
            gc.strokeLine(line.x() + xOffset - 4, line.y(), line.x() + xOffset - 4, line.y() + line.height());
        }

        if (line.hasStyledRuns()) {
            for (TextRunLayout run : line.runs()) {
                renderRun(line, run, theme, xOffset);
            }
        } else {
            renderPlainText(line.text(), (float) (line.x() + xOffset), line.getBaselineY(), line.width(),
                    line.fontSize(), line.style(), theme);
        }
    }

    private void renderRun(LineLayout line, TextRunLayout run, ReaderTheme theme, double xOffset) {
        if (run == null || run.isEmpty()) {
            return;
        }

        TextStyle style = run.style() != null ? run.style() : line.style();
        float fontSize = Math.max(6f, run.fontSize());
        float x = (float) (line.x() + run.x() + xOffset);
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

        renderPlainText(run.text(), x, baseline, run.width(), fontSize, style, theme);
    }

    private void renderPlainText(
            String text,
            float x,
            float baseline,
            float width,
            float fontSize,
            TextStyle style,
            ReaderTheme theme
    ) {
        if (text == null || text.isEmpty()) {
            return;
        }

        TextStyle effective = style != null ? style : TextStyle.NORMAL;
        ReaderElementStyle semantic = styleSheet.forTextStyle(effective);
        String fontKey = effective.name() + ':' + semantic.fontFamily() + ':' + semantic.fontWeight() + ':' + Math.round(fontSize * 10f);
        Font font = fontCache.computeIfAbsent(fontKey, k -> fontProvider.getFont(effective, fontSize, semantic));
        String textColor = semantic.color() == null || semantic.color().isBlank()
                ? defaultColor(effective, theme)
                : semantic.color();

        gc.setFont(font);
        gc.setFill(Color.web(textColor));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        gc.fillText(text, x, baseline);

        if (effective == TextStyle.UNDERLINE || effective == TextStyle.LINK) {
            gc.setStroke(Color.web(textColor));
            gc.strokeLine(x, baseline + 2, x + width, baseline + 2);
        }
        if (effective == TextStyle.STRIKETHROUGH) {
            gc.setStroke(Color.web(theme.foreground()));
            gc.strokeLine(x, baseline - fontSize * 0.30f,
                    x + width, baseline - fontSize * 0.30f);
        }
    }

    private String defaultColor(TextStyle style, ReaderTheme theme) {
        return switch (ReaderStyleSheet.semanticElement(style)) {
            case LINK -> theme.linkColor();
            case SUBTITLE, EPIGRAPH, POEM_AUTHOR, TEXT_AUTHOR, FOOTNOTE -> theme.secondaryText();
            default -> theme.foreground();
        };
    }

    private void renderImageMarker(String marker, double x, float y, float requestedHeight, double pageRight) {
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

        double maxWidth = Math.max(1, pageRight - x - 20);
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
                double requestedWidth = Math.min(MAX_DECODE_DIMENSION, Math.max(512.0, canvas.getWidth() * 1.5));
                double requestedHeight = Math.min(MAX_DECODE_DIMENSION, Math.max(512.0, canvas.getHeight() * 1.5));
                Image image = new Image(in, requestedWidth, requestedHeight, true, true);
                if (!image.isError()) {
                    cacheImage(imageId, image);
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
        this.styleSheet = settings.styleSheet() != null ? settings.styleSheet() : ReaderStyleSheet.defaults();
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
        imageCacheBytes = 0L;
    }

    private void cacheImage(String imageId, Image image) {
        long bytes = estimatedDecodedBytes(image);
        if (bytes <= 0 || bytes > MAX_IMAGE_CACHE_BYTES) {
            return;
        }
        Image previous = imageCache.remove(imageId);
        if (previous != null) imageCacheBytes -= estimatedDecodedBytes(previous);
        while (!imageCache.isEmpty() &&
                (imageCache.size() >= MAX_IMAGE_CACHE_ENTRIES || imageCacheBytes + bytes > MAX_IMAGE_CACHE_BYTES)) {
            var iterator = imageCache.entrySet().iterator();
            Map.Entry<String, Image> eldest = iterator.next();
            imageCacheBytes -= estimatedDecodedBytes(eldest.getValue());
            iterator.remove();
        }
        imageCache.put(imageId, image);
        imageCacheBytes += bytes;
    }

    private long estimatedDecodedBytes(Image image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return 0L;
        double pixels = image.getWidth() * image.getHeight();
        if (pixels >= Long.MAX_VALUE / 4.0) return Long.MAX_VALUE;
        return Math.max(0L, (long) Math.ceil(pixels * 4.0));
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public GraphicsContext getGraphicsContext() {
        return gc;
    }

}
