package com.myhomelibcorp.reader.api;

public record ReaderTheme(
        String name,
        String displayName,
        String background,
        String foreground,
        String secondaryText,
        String linkColor,
        String selectionColor,
        String quoteBackground,
        String quoteBorder,
        String codeBackground
) {
    public static ReaderTheme light() {
        return new ReaderTheme(
                "light", "Світла",
                "#ffffff", "#111111", "#555555",
                "#2196F3", "#B3D4FC",
                "#f9f9f9", "#cccccc", "#f5f5f5"
        );
    }

    public static ReaderTheme sepia() {
        return new ReaderTheme(
                "sepia", "Селія",
                "#f5ecd9", "#331f0a", "#6b5a4a",
                "#2196F3", "#D4C5A9",
                "#e8dcc8", "#8b7355", "#e8dcc8"
        );
    }

    public static ReaderTheme dark() {
        return new ReaderTheme(
                "dark", "Темна",
                "#1a1a1a", "#e0e0e0", "#888888",
                "#64B5F6", "#4A4A4A",
                "#2a2a2a", "#555555", "#2a2a2a"
        );
    }

    public static ReaderTheme amoled() {
        return new ReaderTheme(
                "amoled", "AMOLED",
                "#000000", "#e0e0e0", "#666666",
                "#64B5F6", "#333333",
                "#111111", "#333333", "#111111"
        );
    }

    public static ReaderTheme fromName(String name) {
        return switch (name) {
            case "sepia" -> sepia();
            case "dark" -> dark();
            case "amoled" -> amoled();
            default -> light();
        };
    }

    /** Builds a base theme and optionally overrides reader foreground/background from custom CSS variables. */
    public static ReaderTheme fromSettings(ReaderSettings settings) {
        ReaderSettings effective = settings != null ? settings : ReaderSettings.defaultSettings();
        ReaderTheme base = fromName(effective.themeName());
        String css = effective.customCss();
        if (css == null || css.isBlank()) return base;

        String background = cssVariable(css, "--reader-background", base.background());
        String foreground = cssVariable(css, "--reader-foreground", base.foreground());
        if (background.equals(base.background()) && foreground.equals(base.foreground())) return base;
        return new ReaderTheme(
                base.name(), base.displayName(), background, foreground,
                base.secondaryText(), base.linkColor(), base.selectionColor(),
                base.quoteBackground(), base.quoteBorder(), base.codeBackground()
        );
    }

    private static String cssVariable(String css, String variable, String fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(variable) + "\\s*:\\s*(#[0-9a-fA-F]{6,8})\\s*;?")
                .matcher(css);
        return matcher.find() ? matcher.group(1) : fallback;
    }
}