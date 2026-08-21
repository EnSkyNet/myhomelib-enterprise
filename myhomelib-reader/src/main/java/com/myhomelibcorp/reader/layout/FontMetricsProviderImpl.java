package com.myhomelibcorp.reader.layout;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.TextStyle;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class FontMetricsProviderImpl implements FontMetricsProvider {

    private final ReaderSettings settings;
    private final Map<String, Float> charWidthCache = new HashMap<>();
    private final Map<String, Float> stringWidthCache = new HashMap<>();

    private static final float AVERAGE_CHAR_WIDTH_RATIO = 0.5f;
    private static final float LINE_HEIGHT_RATIO = 1.2f;
    private static final float SPACE_WIDTH_RATIO = 0.3f;

    public FontMetricsProviderImpl(ReaderSettings settings) {
        this.settings = settings;
    }

    @Override
    public float getCharWidth(char c, TextStyle style, float fontSize) {
        String key = style + "_" + fontSize + "_" + c;
        return charWidthCache.computeIfAbsent(key, k -> {
            float base = fontSize * AVERAGE_CHAR_WIDTH_RATIO;
            if (style == TextStyle.BOLD || style == TextStyle.BOLD_ITALIC) {
                base *= 1.05f;
            }
            return base;
        });
    }

    @Override
    public float getStringWidth(String text, TextStyle style, float fontSize) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String key = style + "_" + fontSize + "_" + text;
        return stringWidthCache.computeIfAbsent(key, k -> {
            float total = 0;
            for (char c : text.toCharArray()) {
                total += getCharWidth(c, style, fontSize);
            }
            return total;
        });
    }

    @Override
    public float getLineHeight(TextStyle style, float fontSize, float lineSpacing) {
        return getFontHeight(style, fontSize) * lineSpacing;
    }

    @Override
    public float getFontHeight(TextStyle style, float fontSize) {
        float base = fontSize * LINE_HEIGHT_RATIO;
        if (style == TextStyle.HEADING_1) {
            base *= 1.8f;
        } else if (style == TextStyle.HEADING_2) {
            base *= 1.5f;
        } else if (style == TextStyle.HEADING_3) {
            base *= 1.3f;
        } else if (style == TextStyle.HEADING_4) {
            base *= 1.1f;
        }
        return base;
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
        return true;
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
        return new FontMetricsProviderImpl(newSettings);
    }

    public void clearCache() {
        charWidthCache.clear();
        stringWidthCache.clear();
    }

    public String getCacheStats() {
        return "CharWidth: " + charWidthCache.size() +
                ", StringWidth: " + stringWidthCache.size();
    }
}