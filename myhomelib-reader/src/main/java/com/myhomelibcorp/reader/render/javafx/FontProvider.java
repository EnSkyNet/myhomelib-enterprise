package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.TextStyle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

/**
 * Провайдер шрифтів для JavaFX.
 */
public class FontProvider {

    private final String defaultFontFamily;

    public FontProvider(String defaultFontFamily) {
        this.defaultFontFamily = defaultFontFamily != null ? defaultFontFamily : "Georgia";
    }

    /**
     * Отримує шрифт для заданого стилю та розміру.
     */
    public Font getFont(TextStyle style, float size) {
        if (style == null) {
            style = TextStyle.NORMAL;
        }

        String family = defaultFontFamily;

        FontWeight weight = FontWeight.NORMAL;
        FontPosture posture = FontPosture.REGULAR;

        if (style.isHeading()) {
            int level = style.getHeadingLevel();
            weight = FontWeight.BOLD;
            float multiplier = 1.8f - (level - 1) * 0.15f;
            size *= multiplier;
        }

        switch (style) {
            case BOLD, BOLD_ITALIC -> weight = FontWeight.BOLD;
            case ITALIC -> posture = FontPosture.ITALIC;
        }

        // Для CODE використовуємо моноширинний шрифт
        if (style == TextStyle.CODE) {
            family = "Courier New";
        }

        return Font.font(family, weight, posture, size);
    }

    /**
     * Отримує стандартний шрифт.
     */
    public Font getDefaultFont(float size) {
        return Font.font(defaultFontFamily, size);
    }

    /**
     * Змінює шрифт за замовчуванням.
     */
    public FontProvider withFontFamily(String newFamily) {
        return new FontProvider(newFamily);
    }
}