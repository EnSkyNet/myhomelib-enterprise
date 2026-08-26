package com.myhomelibcorp.reader.layout;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.TextStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextLineLayoutSupportTest {
    @Test
    void hyphenatesWithoutMutatingSourceBoundary() {
        ReaderSettings settings = ReaderSettings.defaultSettings();
        TextLineLayoutSupport support = new TextLineLayoutSupport(new FixedMetrics(settings), settings);

        var line = support.findLineEnd("бібліотека", 0, 45f, TextStyle.NORMAL, 18f, List.of(), "uk");

        assertThat(line.hyphenated()).isTrue();
        assertThat(line.end()).isEqualTo(3);
        assertThat("бібліотека".substring(0, line.end())).isEqualTo("біб");
    }

    @Test
    void settingsUpdateChangesAlignmentWithoutRebuildingPageOrchestrator() {
        ReaderSettings left = new ReaderSettings(
                "light", "Georgia", 18, 1.6, 1.5, 1.5, "left",
                30, 30, 20, 20, true, false, false, 3, true, "",
                true, true, true, true,
                "previous-page", "toggle-toolbar", "next-page");
        ReaderSettings right = new ReaderSettings(
                "light", "Georgia", 18, 1.6, 1.5, 1.5, "right",
                30, 30, 20, 20, true, false, false, 3, true, "",
                true, true, true, true,
                "previous-page", "toggle-toolbar", "next-page");
        FixedMetrics metrics = new FixedMetrics(left);
        TextLineLayoutSupport support = new TextLineLayoutSupport(metrics, left);
        assertThat(support.resolveLineX(10, 100, 5, 40)).isEqualTo(15f);
        support.update(metrics, right);
        assertThat(support.resolveLineX(10, 100, 5, 40)).isEqualTo(70f);
    }

    private static final class FixedMetrics implements FontMetricsProvider {
        private final ReaderSettings settings;
        private FixedMetrics(ReaderSettings settings) { this.settings = settings; }
        @Override public float getCharWidth(char c, TextStyle style, float fontSize) { return 10f; }
        @Override public float getStringWidth(String text, TextStyle style, float fontSize) { return text.length() * 10f; }
        @Override public float getLineHeight(TextStyle style, float fontSize, float lineSpacing) { return 20f; }
        @Override public float getFontHeight(TextStyle style, float fontSize) { return 18f; }
        @Override public float getAverageCharWidth(TextStyle style, float fontSize) { return 10f; }
        @Override public float getSpaceWidth(TextStyle style, float fontSize) { return 10f; }
        @Override public boolean isFontSupported(String fontFamily) { return true; }
        @Override public java.util.List<String> getAvailableFonts() { return java.util.List.of(settings.fontFamily()); }
        @Override public FontMetricsProvider withSettings(ReaderSettings newSettings) { return new FixedMetrics(newSettings); }
    }
}
