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
        String customCss,
        boolean showStatusBar,
        boolean showStatusProgress,
        boolean showStatusChapter,
        boolean showStatusPage,
        String tapLeftAction,
        String tapCenterAction,
        String tapRightAction
) {
    public ReaderSettings {
        themeName = blank(themeName) ? "light" : themeName;
        fontFamily = blank(fontFamily) ? "Georgia" : fontFamily;
        alignment = blank(alignment) ? "justify" : alignment;
        customCss = customCss == null ? "" : customCss;
        tapLeftAction = blank(tapLeftAction) ? "previous-page" : tapLeftAction;
        tapCenterAction = blank(tapCenterAction) ? "toggle-toolbar" : tapCenterAction;
        tapRightAction = blank(tapRightAction) ? "next-page" : tapRightAction;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static ReaderSettings defaultSettings() {
        return new ReaderSettings(
                "light", "Georgia", 18.0, 1.6, 1.5, 1.5, "justify",
                30, 30, 20, 20, true, false, false, 3, true, "",
                true, true, true, true,
                "previous-page", "toggle-toolbar", "next-page"
        );
    }

    public ReaderSettings withFontSize(double newSize) {
        return copy(themeName, fontFamily, newSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode,
                autoScroll, scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction);
    }

    public ReaderSettings withTheme(String newTheme) {
        return copy(newTheme, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode,
                autoScroll, scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction);
    }

    public ReaderSettings withFontFamily(String newFamily) {
        return copy(themeName, newFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode,
                autoScroll, scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction);
    }

    public ReaderSettings withPageMode(boolean enabled) {
        return copy(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, enabled,
                autoScroll, scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction);
    }

    public ReaderSettings withAutoScroll(boolean enabled) {
        return copy(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode,
                enabled, scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction);
    }

    public ReaderSettings withStatusBar(boolean enabled) {
        return copy(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode,
                autoScroll, scrollSpeed, showToolbar, customCss, enabled, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction);
    }

    private static ReaderSettings copy(
            String themeName, String fontFamily, double fontSize, double lineSpacing,
            double paragraphSpacing, double firstLineIndent, String alignment,
            double leftMargin, double rightMargin, double topMargin, double bottomMargin,
            boolean hyphenation, boolean pageMode, boolean autoScroll, int scrollSpeed,
            boolean showToolbar, String customCss, boolean showStatusBar,
            boolean showStatusProgress, boolean showStatusChapter, boolean showStatusPage,
            String tapLeftAction, String tapCenterAction, String tapRightAction) {
        return new ReaderSettings(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing,
                firstLineIndent, alignment, leftMargin, rightMargin, topMargin, bottomMargin,
                hyphenation, pageMode, autoScroll, scrollSpeed, showToolbar, customCss,
                showStatusBar, showStatusProgress, showStatusChapter, showStatusPage,
                tapLeftAction, tapCenterAction, tapRightAction);
    }
}
