package com.myhomelibcorp.reader.api;

import java.util.Locale;

/**
 * Configurable Reader input map inspired by the useful parts of AlReader-style navigation.
 * It is UI-independent and keeps tap/gesture routing out of ReaderCanvas conditionals.
 */
public record ReaderInputSettings(
        String topLeft, String topCenter, String topRight,
        String middleLeft, String middleCenter, String middleRight,
        String bottomLeft, String bottomCenter, String bottomRight,
        String longTopLeft, String longTopCenter, String longTopRight,
        String longMiddleLeft, String longMiddleCenter, String longMiddleRight,
        String longBottomLeft, String longBottomCenter, String longBottomRight,
        String swipeLeft, String swipeRight, String swipeUp, String swipeDown,
        boolean pinchZoom
) {
    public ReaderInputSettings {
        topLeft = action(topLeft, "previous-page");
        topCenter = action(topCenter, "previous-chapter");
        topRight = action(topRight, "next-page");
        middleLeft = action(middleLeft, "previous-page");
        middleCenter = action(middleCenter, "toggle-toolbar");
        middleRight = action(middleRight, "next-page");
        bottomLeft = action(bottomLeft, "previous-page");
        bottomCenter = action(bottomCenter, "search");
        bottomRight = action(bottomRight, "next-page");

        longTopLeft = action(longTopLeft, "previous-chapter");
        longTopCenter = action(longTopCenter, "none");
        longTopRight = action(longTopRight, "next-chapter");
        longMiddleLeft = action(longMiddleLeft, "previous-chapter");
        longMiddleCenter = action(longMiddleCenter, "search");
        longMiddleRight = action(longMiddleRight, "next-chapter");
        longBottomLeft = action(longBottomLeft, "start");
        longBottomCenter = action(longBottomCenter, "toggle-toolbar");
        longBottomRight = action(longBottomRight, "end");

        swipeLeft = action(swipeLeft, "next-page");
        swipeRight = action(swipeRight, "previous-page");
        swipeUp = action(swipeUp, "next-chapter");
        swipeDown = action(swipeDown, "previous-chapter");
    }

    public static ReaderInputSettings defaults() {
        return new ReaderInputSettings(
                "previous-page", "previous-chapter", "next-page",
                "previous-page", "toggle-toolbar", "next-page",
                "previous-page", "search", "next-page",
                "previous-chapter", "none", "next-chapter",
                "previous-chapter", "search", "next-chapter",
                "start", "toggle-toolbar", "end",
                "next-page", "previous-page", "next-chapter", "previous-chapter",
                true);
    }

    /** Backward compatibility for the former three horizontal tap zones. */
    public static ReaderInputSettings fromLegacy(String left, String center, String right) {
        ReaderInputSettings d = defaults();
        return new ReaderInputSettings(
                d.topLeft, d.topCenter, d.topRight,
                action(left, d.middleLeft), action(center, d.middleCenter), action(right, d.middleRight),
                d.bottomLeft, d.bottomCenter, d.bottomRight,
                d.longTopLeft, d.longTopCenter, d.longTopRight,
                d.longMiddleLeft, d.longMiddleCenter, d.longMiddleRight,
                d.longBottomLeft, d.longBottomCenter, d.longBottomRight,
                d.swipeLeft, d.swipeRight, d.swipeUp, d.swipeDown, d.pinchZoom);
    }

    public String tapAction(double xFraction, double yFraction, boolean longPress) {
        int column = xFraction < 1.0 / 3.0 ? 0 : (xFraction >= 2.0 / 3.0 ? 2 : 1);
        int row = yFraction < 1.0 / 3.0 ? 0 : (yFraction >= 2.0 / 3.0 ? 2 : 1);
        if (longPress) {
            return switch (row * 3 + column) {
                case 0 -> longTopLeft; case 1 -> longTopCenter; case 2 -> longTopRight;
                case 3 -> longMiddleLeft; case 4 -> longMiddleCenter; case 5 -> longMiddleRight;
                case 6 -> longBottomLeft; case 7 -> longBottomCenter; default -> longBottomRight;
            };
        }
        return switch (row * 3 + column) {
            case 0 -> topLeft; case 1 -> topCenter; case 2 -> topRight;
            case 3 -> middleLeft; case 4 -> middleCenter; case 5 -> middleRight;
            case 6 -> bottomLeft; case 7 -> bottomCenter; default -> bottomRight;
        };
    }

    private static String action(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }
}
