package com.myhomelibcorp.reader.model;

import com.myhomelibcorp.reader.api.TextStyle;

/**
 * Розкладка одного рядка тексту.
 */
public record LineLayout(
        String text,
        float x,
        float y,
        float width,
        float height,
        int paragraphIndex,
        int lineIndex,
        TextStyle style,
        long textOffset,
        int charLength
) {
    public boolean isEmpty() {
        return text == null || text.isEmpty();
    }

    public float getAscent() {
        return height * 0.75f;
    }

    public float getDescent() {
        return height - getAscent();
    }

    public float getBaselineY() {
        return y + getAscent();
    }

    public static LineLayout empty() {
        return new LineLayout("", 0, 0, 0, 0, 0, 0, TextStyle.NORMAL, 0, 0);
    }

    @Override
    public String toString() {
        return "LineLayout{" +
                "text='" + (text != null && text.length() > 20 ? text.substring(0, 20) + "..." : text) + '\'' +
                ", x=" + x +
                ", y=" + y +
                ", width=" + width +
                ", height=" + height +
                '}';
    }
}