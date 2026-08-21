package com.myhomelibcorp.reader.layout;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.TextStyle;

import java.util.List;

public interface FontMetricsProvider {

    float getCharWidth(char c, TextStyle style, float fontSize);

    float getStringWidth(String text, TextStyle style, float fontSize);

    float getLineHeight(TextStyle style, float fontSize, float lineSpacing);

    float getFontHeight(TextStyle style, float fontSize);

    float getAverageCharWidth(TextStyle style, float fontSize);

    float getSpaceWidth(TextStyle style, float fontSize);

    boolean isFontSupported(String fontFamily);

    List<String> getAvailableFonts();

    FontMetricsProvider withSettings(ReaderSettings settings);
}