package com.myhomelibcorp.reader.model;

import com.myhomelibcorp.reader.api.TextStyle;

/**
 * Невеликий стилізований фрагмент усередині одного візуального рядка.
 *
 * Це дозволяє renderer-у малювати FB2 inline-стилі (strong/emphasis/link/code)
 * без WebView і без створення HTML/DOM-представлення всієї книги.
 */
public record TextRunLayout(
        String text,
        float x,
        float width,
        float fontSize,
        TextStyle style,
        long textOffset,
        int charLength
) {
    public boolean isEmpty() {
        return text == null || text.isEmpty();
    }
}
