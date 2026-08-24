package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.layout.FontMetricsProvider;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Font metrics backed by the same JavaFX font engine that paints ReaderCanvas.
 *
 * The old generic provider intentionally used approximate em ratios. That is a
 * useful headless fallback, but it is not precise enough for styled/justified
 * text: accumulated width error can place the next visual run on top of the
 * previous one and make spaces appear to disappear. Desktop Reader uses this
 * implementation so layout and rendering agree on glyph widths.
 */
public final class JavaFxFontMetricsProvider implements FontMetricsProvider {

    private final ReaderSettings settings;
    private final FontProvider fontProvider;
    private final Map<String, Float> charWidthCache = new HashMap<>();
    private final Map<String, Float> fontHeightCache = new HashMap<>();

    public JavaFxFontMetricsProvider(ReaderSettings settings) {
        this.settings = settings != null ? settings : ReaderSettings.defaultSettings();
        this.fontProvider = new FontProvider(this.settings.fontFamily());
    }

    @Override
    public float getCharWidth(char c, TextStyle style, float fontSize) {
        String key = styleKey(style, fontSize) + ':' + (int) c;
        return charWidthCache.computeIfAbsent(key, ignored -> measure(String.valueOf(c), style, fontSize));
    }

    @Override
    public float getStringWidth(String text, TextStyle style, float fontSize) {
        if (text == null || text.isEmpty()) return 0f;
        return measure(text, style, fontSize);
    }

    @Override
    public float getLineHeight(TextStyle style, float fontSize, float lineSpacing) {
        return getFontHeight(style, fontSize) * Math.max(0.8f, lineSpacing);
    }

    @Override
    public float getFontHeight(TextStyle style, float fontSize) {
        String key = styleKey(style, fontSize);
        return fontHeightCache.computeIfAbsent(key, ignored -> {
            Text sample = new Text("AgЙц");
            sample.setFont(fontProvider.getFont(effective(style), fontSize));
            return (float) Math.max(fontSize, sample.getLayoutBounds().getHeight());
        });
    }

    @Override
    public float getAverageCharWidth(TextStyle style, float fontSize) {
        return getStringWidth("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", style, fontSize) / 52f;
    }

    @Override
    public float getSpaceWidth(TextStyle style, float fontSize) {
        return getCharWidth(' ', style, fontSize);
    }

    @Override
    public boolean isFontSupported(String fontFamily) {
        return fontFamily != null && Font.getFamilies().contains(fontFamily);
    }

    @Override
    public List<String> getAvailableFonts() {
        return List.copyOf(Font.getFamilies());
    }

    @Override
    public FontMetricsProvider withSettings(ReaderSettings newSettings) {
        return new JavaFxFontMetricsProvider(newSettings != null ? newSettings : settings);
    }

    private float measure(String text, TextStyle style, float fontSize) {
        Text node = new Text(text);
        node.setFont(fontProvider.getFont(effective(style), Math.max(1f, fontSize)));
        return (float) node.getLayoutBounds().getWidth();
    }

    private static TextStyle effective(TextStyle style) {
        return style != null ? style : TextStyle.NORMAL;
    }

    private static String styleKey(TextStyle style, float fontSize) {
        return effective(style).name() + ':' + Math.round(fontSize * 10f);
    }
}
