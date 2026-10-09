package com.myhomelibcorp.ui.util;

/** UTF-16 cut-point normalization for UI text truncation without changing presentation policy. */
public final class Utf16BoundarySupport {
    private Utf16BoundarySupport() { }

    public static int safeCut(String value, int requestedEnd) {
        if (value == null || value.isEmpty() || requestedEnd <= 0) return 0;
        int end = Math.min(requestedEnd, value.length());
        if (end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        while (end > 0 && end < value.length() && isCombiningMark(value.codePointAt(end))) {
            int previous = value.codePointBefore(end);
            end -= Character.charCount(previous);
        }
        return end;
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}
