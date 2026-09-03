package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderElementStyle;
import com.myhomelibcorp.reader.api.TextStyle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

import java.util.Locale;

/** JavaFX font adapter. Розмір уже обчислений layout-engine і тут не масштабується вдруге. */
public class FontProvider {

    private final String defaultFontFamily;

    public FontProvider(String defaultFontFamily) {
        this.defaultFontFamily = defaultFontFamily != null && !defaultFontFamily.isBlank()
                ? defaultFontFamily
                : "Georgia";
    }

    public Font getFont(TextStyle style, float size) {
        return getFont(style, size, null);
    }

    public Font getFont(TextStyle style, float size, ReaderElementStyle semanticStyle) {
        TextStyle effective = style != null ? style : TextStyle.NORMAL;
        String family = semanticStyle != null && semanticStyle.fontFamily() != null && !semanticStyle.fontFamily().isBlank()
                ? semanticStyle.fontFamily()
                : (effective == TextStyle.CODE ? "Monospaced" : defaultFontFamily);

        FontWeight weight = semanticWeight(semanticStyle);
        if (weight == null) {
            weight = switch (effective) {
                case BOLD, BOLD_ITALIC, STRONG,
                     HEADING_1, HEADING_2, HEADING_3, HEADING_4, HEADING_5, HEADING_6,
                     BOOK_TITLE, CHAPTER_TITLE, SECTION_TITLE, SUBTITLE -> FontWeight.BOLD;
                default -> FontWeight.NORMAL;
            };
        }

        FontPosture posture = semanticPosture(semanticStyle);
        if (posture == null) {
            posture = switch (effective) {
                case ITALIC, BOLD_ITALIC, EMPHASIS, CITE, EPIGRAPH, POEM_AUTHOR, TEXT_AUTHOR -> FontPosture.ITALIC;
                default -> FontPosture.REGULAR;
            };
        }

        return Font.font(family, weight, posture, Math.max(1, size));
    }

    private static FontWeight semanticWeight(ReaderElementStyle style) {
        if (style == null || style.fontWeight() == null || style.fontWeight().isBlank()) return null;
        String value = style.fontWeight().toLowerCase(Locale.ROOT).trim();
        if (value.matches(".*\b(700|800|900|bold)\b.*")) return FontWeight.BOLD;
        if (value.matches(".*\b(600|semibold|semi-bold)\b.*")) return FontWeight.SEMI_BOLD;
        if (value.matches(".*\b(500|medium)\b.*")) return FontWeight.MEDIUM;
        if (value.matches(".*\b(300|light)\b.*")) return FontWeight.LIGHT;
        if (value.matches(".*\b(400|normal|regular)\b.*")) return FontWeight.NORMAL;
        return null;
    }

    private static FontPosture semanticPosture(ReaderElementStyle style) {
        if (style == null || style.fontWeight() == null || style.fontWeight().isBlank()) return null;
        String value = style.fontWeight().toLowerCase(Locale.ROOT);
        return value.contains("italic") ? FontPosture.ITALIC : null;
    }

    public Font getDefaultFont(float size) {
        return Font.font(defaultFontFamily, Math.max(1, size));
    }

    public FontProvider withFontFamily(String newFamily) {
        return new FontProvider(newFamily);
    }
}
