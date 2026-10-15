package com.myhomelibcorp.infrastructure.content;

import java.io.IOException;

/** Normalizes across input chunks and checks the budget before retaining each character. */
final class NormalizedTextBuffer {
    private final int maxChars;
    private final String contentKind;
    private final StringBuilder text = new StringBuilder();
    private boolean pendingSpace;

    NormalizedTextBuffer(int maxChars) {
        this(maxChars, "text");
    }

    NormalizedTextBuffer(int maxChars, String contentKind) {
        if (maxChars < 0) throw new IllegalArgumentException("Negative text budget");
        this.maxChars = maxChars;
        this.contentKind = contentKind;
    }

    void append(char[] chars, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, chars.length);
        for (int i = offset; i < offset + length; i++) append(chars[i]);
    }

    void append(char value) throws IOException {
        if (Character.isWhitespace(value) || value == '\u00A0') {
            separate();
            return;
        }
        int added = pendingSpace ? 2 : 1;
        if ((long) text.length() + added > maxChars) {
            throw new IOException("Extracted " + contentKind + " exceeds safety limit (remaining budget: "
                    + maxChars + " characters)");
        }
        if (pendingSpace) text.append(' ');
        text.append(value);
        pendingSpace = false;
    }

    void separate() { pendingSpace = !text.isEmpty(); }
    boolean isEmpty() { return text.isEmpty(); }
    String text() { return text.toString(); }
}
