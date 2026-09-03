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
        /** Legacy setting retained for backward-compatible JSON/domain mapping; page numbers are controlled by showStatusPage. */
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
        String tapRightAction,
        boolean twoPageMode,
        boolean autoTwoPageLandscape,
        boolean showStatusClock,
        ReaderInputSettings input,
        ReaderStyleSheet styleSheet
) {
    public ReaderSettings {
        themeName = blank(themeName) ? "light" : themeName;
        fontFamily = blank(fontFamily) ? "Georgia" : fontFamily;
        alignment = blank(alignment) ? "justify" : alignment;
        customCss = customCss == null ? "" : customCss;
        tapLeftAction = blank(tapLeftAction) ? "previous-page" : tapLeftAction;
        tapCenterAction = blank(tapCenterAction) ? "toggle-toolbar" : tapCenterAction;
        tapRightAction = blank(tapRightAction) ? "next-page" : tapRightAction;
        input = input == null ? ReaderInputSettings.fromLegacy(tapLeftAction, tapCenterAction, tapRightAction) : input;
        styleSheet = styleSheet == null ? ReaderStyleSheet.defaults() : ReaderStyleSheet.withOverrides(styleSheet.styles());
    }

    /** Source-compatible constructor for v7/early-v7.1 callers and tests. */
    public ReaderSettings(
            String themeName, String fontFamily, double fontSize, double lineSpacing,
            double paragraphSpacing, double firstLineIndent, String alignment,
            double leftMargin, double rightMargin, double topMargin, double bottomMargin,
            boolean hyphenation, boolean pageMode, boolean autoScroll, int scrollSpeed,
            boolean showToolbar, String customCss, boolean showStatusBar,
            boolean showStatusProgress, boolean showStatusChapter, boolean showStatusPage,
            String tapLeftAction, String tapCenterAction, String tapRightAction) {
        this(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent, alignment,
                leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode, autoScroll,
                scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress, showStatusChapter,
                showStatusPage, tapLeftAction, tapCenterAction, tapRightAction,
                false, true, false, ReaderInputSettings.fromLegacy(tapLeftAction, tapCenterAction, tapRightAction),
                ReaderStyleSheet.defaults());
    }

    /** Source-compatible constructor for callers using the pre-semantic-style full v7.1 signature. */
    public ReaderSettings(
            String themeName, String fontFamily, double fontSize, double lineSpacing,
            double paragraphSpacing, double firstLineIndent, String alignment,
            double leftMargin, double rightMargin, double topMargin, double bottomMargin,
            boolean hyphenation, boolean pageMode, boolean autoScroll, int scrollSpeed,
            boolean showToolbar, String customCss, boolean showStatusBar,
            boolean showStatusProgress, boolean showStatusChapter, boolean showStatusPage,
            String tapLeftAction, String tapCenterAction, String tapRightAction,
            boolean twoPageMode, boolean autoTwoPageLandscape, boolean showStatusClock,
            ReaderInputSettings input) {
        this(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent, alignment,
                leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode, autoScroll,
                scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress, showStatusChapter,
                showStatusPage, tapLeftAction, tapCenterAction, tapRightAction,
                twoPageMode, autoTwoPageLandscape, showStatusClock, input, ReaderStyleSheet.defaults());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public static ReaderSettings defaultSettings() {
        ReaderInputSettings input = ReaderInputSettings.defaults();
        return new ReaderSettings(
                "light", "Georgia", 18.0, 1.6, 1.5, 1.5, "justify",
                30, 30, 20, 20, true, false, false, 3, true, "",
                true, true, true, true,
                input.middleLeft(), input.middleCenter(), input.middleRight(),
                false, true, false, input, ReaderStyleSheet.defaults());
    }

    public ReaderSettings withFontSize(double newSize) { return copy(themeName, fontFamily, newSize, input, twoPageMode); }
    public ReaderSettings withTheme(String newTheme) { return copy(newTheme, fontFamily, fontSize, input, twoPageMode); }
    public ReaderSettings withFontFamily(String newFamily) { return copy(themeName, newFamily, fontSize, input, twoPageMode); }
    public ReaderSettings withPageMode(boolean enabled) {
        return fullCopy(themeName, fontFamily, fontSize, input, twoPageMode, autoTwoPageLandscape,
                showStatusClock, enabled, autoScroll);
    }
    public ReaderSettings withAutoScroll(boolean enabled) {
        return fullCopy(themeName, fontFamily, fontSize, input, twoPageMode, autoTwoPageLandscape,
                showStatusClock, pageMode, enabled);
    }
    public ReaderSettings withStatusBar(boolean enabled) {
        return new ReaderSettings(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode,
                autoScroll, scrollSpeed, showToolbar, customCss, enabled, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction,
                twoPageMode, autoTwoPageLandscape, showStatusClock, input, styleSheet);
    }
    public ReaderSettings withTwoPageMode(boolean enabled) {
        return fullCopy(themeName, fontFamily, fontSize, input, enabled, autoTwoPageLandscape,
                showStatusClock, pageMode, autoScroll);
    }
    public ReaderSettings withInput(ReaderInputSettings value) {
        ReaderInputSettings effective = value == null ? ReaderInputSettings.defaults() : value;
        return new ReaderSettings(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode,
                autoScroll, scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress,
                showStatusChapter, showStatusPage, effective.middleLeft(), effective.middleCenter(), effective.middleRight(),
                twoPageMode, autoTwoPageLandscape, showStatusClock, effective, styleSheet);
    }
    public ReaderSettings withStyleSheet(ReaderStyleSheet value) {
        return new ReaderSettings(themeName, fontFamily, fontSize, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, pageMode,
                autoScroll, scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction,
                twoPageMode, autoTwoPageLandscape, showStatusClock, input,
                value == null ? ReaderStyleSheet.defaults() : value);
    }

    private ReaderSettings copy(String theme, String family, double size, ReaderInputSettings inputs, boolean spread) {
        return fullCopy(theme, family, size, inputs, spread, autoTwoPageLandscape, showStatusClock, pageMode, autoScroll);
    }

    private ReaderSettings fullCopy(String theme, String family, double size, ReaderInputSettings inputs,
                                    boolean spread, boolean autoSpread, boolean clock,
                                    boolean legacyPageMode, boolean autoScrollValue) {
        return new ReaderSettings(theme, family, size, lineSpacing, paragraphSpacing, firstLineIndent,
                alignment, leftMargin, rightMargin, topMargin, bottomMargin, hyphenation, legacyPageMode,
                autoScrollValue, scrollSpeed, showToolbar, customCss, showStatusBar, showStatusProgress,
                showStatusChapter, showStatusPage, tapLeftAction, tapCenterAction, tapRightAction,
                spread, autoSpread, clock, inputs, styleSheet);
    }
}
