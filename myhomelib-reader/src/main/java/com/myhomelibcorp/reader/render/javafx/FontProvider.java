package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.TextStyle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

/** JavaFX font adapter. Розмір уже обчислений layout-engine і тут не масштабується вдруге. */
public class FontProvider {

    private final String defaultFontFamily;

    public FontProvider(String defaultFontFamily) {
        this.defaultFontFamily = defaultFontFamily != null && !defaultFontFamily.isBlank()
                ? defaultFontFamily
                : "Georgia";
    }

    public Font getFont(TextStyle style, float size) {
        TextStyle effective = style != null ? style : TextStyle.NORMAL;
        String family = effective == TextStyle.CODE ? "Monospaced" : defaultFontFamily;

        FontWeight weight = switch (effective) {
            case BOLD, BOLD_ITALIC, STRONG,
                 HEADING_1, HEADING_2, HEADING_3, HEADING_4, HEADING_5, HEADING_6 -> FontWeight.BOLD;
            default -> FontWeight.NORMAL;
        };

        FontPosture posture = switch (effective) {
            case ITALIC, BOLD_ITALIC, EMPHASIS, CITE, EPIGRAPH -> FontPosture.ITALIC;
            default -> FontPosture.REGULAR;
        };

        return Font.font(family, weight, posture, Math.max(1, size));
    }

    public Font getDefaultFont(float size) {
        return Font.font(defaultFontFamily, Math.max(1, size));
    }

    public FontProvider withFontFamily(String newFamily) {
        return new FontProvider(newFamily);
    }
}
