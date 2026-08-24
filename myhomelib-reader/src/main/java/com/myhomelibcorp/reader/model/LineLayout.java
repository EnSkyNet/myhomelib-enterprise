package com.myhomelibcorp.reader.model;

import com.myhomelibcorp.reader.api.TextStyle;

import java.util.List;

/**
 * Розкладка одного рядка тексту.
 *
 * fontSize зберігається окремо від height: height — це висота рядка з
 * урахуванням міжрядкового інтервалу, fontSize — базовий розмір шрифту.
 * runs містить стилізовані inline-фрагменти і залишається порожнім для
 * простого одностильового рядка.
 */
public record LineLayout(
        String text,
        float x,
        float y,
        float width,
        float height,
        float fontSize,
        int paragraphIndex,
        int lineIndex,
        TextStyle style,
        long textOffset,
        int charLength,
        List<TextRunLayout> runs
) {
    /** Сумісний конструктор для старого коду/тестів. */
    public LineLayout(
            String text,
            float x,
            float y,
            float width,
            float height,
            float fontSize,
            int paragraphIndex,
            int lineIndex,
            TextStyle style,
            long textOffset,
            int charLength
    ) {
        this(text, x, y, width, height, fontSize, paragraphIndex, lineIndex,
                style, textOffset, charLength, List.of());
    }

    public LineLayout {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    public boolean isEmpty() {
        return text == null || text.isEmpty();
    }

    public boolean hasStyledRuns() {
        return !runs.isEmpty();
    }

    public float getAscent() {
        // Для Canvas цього достатньо як швидкої кросплатформної апроксимації.
        return fontSize * 0.82f;
    }

    public float getDescent() {
        return Math.max(0, height - getAscent());
    }

    public float getBaselineY() {
        return y + getAscent();
    }

    public static LineLayout empty() {
        return new LineLayout("", 0, 0, 0, 0, 0, 0, 0,
                TextStyle.NORMAL, 0, 0, List.of());
    }

    @Override
    public String toString() {
        return "LineLayout{" +
                "text='" + (text != null && text.length() > 20 ? text.substring(0, 20) + "..." : text) + '\'' +
                ", x=" + x +
                ", y=" + y +
                ", width=" + width +
                ", height=" + height +
                ", fontSize=" + fontSize +
                ", runs=" + runs.size() +
                '}';
    }
}
