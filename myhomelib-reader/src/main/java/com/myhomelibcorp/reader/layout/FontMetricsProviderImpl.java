package com.myhomelibcorp.reader.layout;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.TextStyle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Легка реалізація метрик без залежності core-layout від JavaFX.
 *
 * Навмисно не кешує цілі рядки: для великих книг такий кеш дуже швидко
 * перетворювався на другу копію значної частини тексту книги.
 */
public class FontMetricsProviderImpl implements FontMetricsProvider {

    private final ReaderSettings settings;
    private final Map<String, Float> charWidthCache = new HashMap<>();

    private static final float AVERAGE_CHAR_WIDTH_RATIO = 0.52f;
    private static final float LINE_HEIGHT_RATIO = 1.18f;
    private static final float SPACE_WIDTH_RATIO = 0.33f;

    public FontMetricsProviderImpl(ReaderSettings settings) {
        this.settings = settings;
    }

    @Override
    public float getCharWidth(char c, TextStyle style, float fontSize) {
        String key = style + "_" + Math.round(fontSize * 10f) + "_" + c;
        return charWidthCache.computeIfAbsent(key, k -> {
            if (Character.isWhitespace(c)) {
                return fontSize * SPACE_WIDTH_RATIO;
            }
            float base = fontSize * AVERAGE_CHAR_WIDTH_RATIO;
            if (style == TextStyle.BOLD || style == TextStyle.BOLD_ITALIC || style == TextStyle.STRONG) {
                base *= 1.05f;
            }
            // Широкі символи (CJK, деякі emoji) грубо оцінюємо як повний em.
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN ||
                    Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL ||
                    Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA ||
                    Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA) {
                base = fontSize;
            }
            return base;
        });
    }

    @Override
    public float getStringWidth(String text, TextStyle style, float fontSize) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        float total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += getCharWidth(text.charAt(i), style, fontSize);
        }
        return total;
    }

    @Override
    public float getLineHeight(TextStyle style, float fontSize, float lineSpacing) {
        return getFontHeight(style, fontSize) * Math.max(0.8f, lineSpacing);
    }

    @Override
    public float getFontHeight(TextStyle style, float fontSize) {
        // Розмір заголовка визначає TextLayoutEngine. Тут не масштабуємо вдруге.
        return fontSize * LINE_HEIGHT_RATIO;
    }

    @Override
    public float getAverageCharWidth(TextStyle style, float fontSize) {
        return fontSize * AVERAGE_CHAR_WIDTH_RATIO;
    }

    @Override
    public float getSpaceWidth(TextStyle style, float fontSize) {
        return fontSize * SPACE_WIDTH_RATIO;
    }

    @Override
    public boolean isFontSupported(String fontFamily) {
        return fontFamily != null && !fontFamily.isBlank();
    }

    @Override
    public List<String> getAvailableFonts() {
        return List.of(
                "Georgia", "Times New Roman", "Arial", "Helvetica",
                "Verdana", "Palatino", "Book Antiqua", "Courier New",
                "Tahoma", "Trebuchet MS"
        );
    }

    @Override
    public FontMetricsProvider withSettings(ReaderSettings newSettings) {
        return new FontMetricsProviderImpl(newSettings != null ? newSettings : settings);
    }

    public void clearCache() {
        charWidthCache.clear();
    }

    public String getCacheStats() {
        return "CharWidth: " + charWidthCache.size();
    }
}
