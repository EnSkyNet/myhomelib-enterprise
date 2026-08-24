package com.myhomelibcorp.reader.api;

public record ReaderSettings(
        String themeName,
        String fontFamily,
        double fontSize,
        double lineSpacing,
        double paragraphSpacing,
        double firstLineIndent,
        String alignment,
        double leftMargin,
        double rightMargin,
        double topMargin,
        double bottomMargin,
        boolean hyphenation,
        boolean pageMode,
        boolean autoScroll,
        int scrollSpeed,
        boolean showToolbar,
        String customCss
) {
    public static ReaderSettings defaultSettings() {
        return new ReaderSettings(
                "light",
                "Georgia",
                18.0,
                1.6,
                1.5,
                1.5,
                "justify",
                30,
                30,
                20,
                20,
                true,
                false,
                false,
                3,
                true,
                ""
        );
    }

    public ReaderSettings withFontSize(double newSize) {
        return new ReaderSettings(
                themeName, fontFamily, newSize, lineSpacing, paragraphSpacing,
                firstLineIndent, alignment, leftMargin, rightMargin,
                topMargin, bottomMargin, hyphenation, pageMode, autoScroll,
                scrollSpeed, showToolbar, customCss
        );
    }

    public ReaderSettings withTheme(String newTheme) {
        return new ReaderSettings(
                newTheme, fontFamily, fontSize, lineSpacing, paragraphSpacing,
                firstLineIndent, alignment, leftMargin, rightMargin,
                topMargin, bottomMargin, hyphenation, pageMode, autoScroll,
                scrollSpeed, showToolbar, customCss
        );
    }

    public ReaderSettings withFontFamily(String newFamily) {
        return new ReaderSettings(
                themeName, newFamily, fontSize, lineSpacing, paragraphSpacing,
                firstLineIndent, alignment, leftMargin, rightMargin,
                topMargin, bottomMargin, hyphenation, pageMode, autoScroll,
                scrollSpeed, showToolbar, customCss
        );
    }

    public ReaderSettings withPageMode(boolean enabled) {
        return new ReaderSettings(
                themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing,
                firstLineIndent, alignment, leftMargin, rightMargin,
                topMargin, bottomMargin, hyphenation, enabled, autoScroll,
                scrollSpeed, showToolbar, customCss
        );
    }

    public ReaderSettings withAutoScroll(boolean enabled) {
        return new ReaderSettings(
                themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing,
                firstLineIndent, alignment, leftMargin, rightMargin,
                topMargin, bottomMargin, hyphenation, pageMode, enabled,
                scrollSpeed, showToolbar, customCss
        );
    }
}