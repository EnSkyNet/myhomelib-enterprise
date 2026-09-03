package com.myhomelibcorp.reader.api;

import java.util.EnumMap;
import java.util.Map;

/** Immutable structured Reader style sheet. customCss remains a legacy escape hatch only. */
public record ReaderStyleSheet(Map<ReaderSemanticElement, ReaderElementStyle> styles) {
    private static final ReaderStyleSheet DEFAULTS = createDefaults();
    public ReaderStyleSheet {
        EnumMap<ReaderSemanticElement, ReaderElementStyle> copy = new EnumMap<>(ReaderSemanticElement.class);
        if (styles != null) {
            styles.forEach((key, value) -> {
                if (key != null && value != null) copy.put(key, value);
            });
        }
        styles = Map.copyOf(copy);
    }

    public static ReaderStyleSheet defaults() {
        return DEFAULTS;
    }

    private static ReaderStyleSheet createDefaults() {
        EnumMap<ReaderSemanticElement, ReaderElementStyle> map = new EnumMap<>(ReaderSemanticElement.class);
        map.put(ReaderSemanticElement.BODY, ReaderElementStyle.inherited(1.00));
        map.put(ReaderSemanticElement.BOOK_TITLE, style(1.55, "bold"));
        map.put(ReaderSemanticElement.CHAPTER_TITLE, style(1.35, "bold"));
        map.put(ReaderSemanticElement.SECTION_TITLE, style(1.20, "bold"));
        map.put(ReaderSemanticElement.SUBTITLE, style(1.10, "bold"));
        map.put(ReaderSemanticElement.EPIGRAPH, ReaderElementStyle.inherited(1.00));
        map.put(ReaderSemanticElement.QUOTE, ReaderElementStyle.inherited(1.00));
        map.put(ReaderSemanticElement.POEM, ReaderElementStyle.inherited(1.00));
        map.put(ReaderSemanticElement.POEM_AUTHOR, ReaderElementStyle.inherited(0.95));
        map.put(ReaderSemanticElement.TEXT_AUTHOR, ReaderElementStyle.inherited(0.95));
        map.put(ReaderSemanticElement.ANNOTATION, ReaderElementStyle.inherited(0.95));
        map.put(ReaderSemanticElement.LINK, ReaderElementStyle.inherited(1.00));
        map.put(ReaderSemanticElement.FOOTNOTE, ReaderElementStyle.inherited(0.90));
        map.put(ReaderSemanticElement.STRONG, style(1.00, "bold"));
        map.put(ReaderSemanticElement.EMPHASIS, ReaderElementStyle.inherited(1.00));
        map.put(ReaderSemanticElement.CODE, new ReaderElementStyle("Monospaced", null, 1.00, "", "", "", null, null));
        return new ReaderStyleSheet(map);
    }

    public static ReaderStyleSheet withOverrides(Map<ReaderSemanticElement, ReaderElementStyle> overrides) {
        EnumMap<ReaderSemanticElement, ReaderElementStyle> merged = new EnumMap<>(ReaderSemanticElement.class);
        merged.putAll(defaults().styles());
        if (overrides != null) overrides.forEach((key, value) -> {
            if (key != null && value != null) merged.put(key, value);
        });
        return new ReaderStyleSheet(merged);
    }

    public ReaderElementStyle forTextStyle(TextStyle style) {
        ReaderSemanticElement element = semanticElement(style);
        ReaderElementStyle value = styles.get(element);
        if (value != null) return value;
        return defaults().styles().getOrDefault(element, ReaderElementStyle.inherited(1.0));
    }

    public ReaderStyleSheet with(ReaderSemanticElement element, ReaderElementStyle style) {
        EnumMap<ReaderSemanticElement, ReaderElementStyle> copy = new EnumMap<>(ReaderSemanticElement.class);
        copy.putAll(styles);
        if (style == null) copy.remove(element); else copy.put(element, style);
        return withOverrides(copy);
    }

    public static ReaderSemanticElement semanticElement(TextStyle style) {
        if (style == null) return ReaderSemanticElement.BODY;
        return switch (style) {
            case BOOK_TITLE -> ReaderSemanticElement.BOOK_TITLE;
            case CHAPTER_TITLE, HEADING_1 -> ReaderSemanticElement.CHAPTER_TITLE;
            case SECTION_TITLE, HEADING_2, HEADING_3, HEADING_4, HEADING_5, HEADING_6 -> ReaderSemanticElement.SECTION_TITLE;
            case SUBTITLE -> ReaderSemanticElement.SUBTITLE;
            case EPIGRAPH -> ReaderSemanticElement.EPIGRAPH;
            case QUOTE, CITE -> ReaderSemanticElement.QUOTE;
            case POEM, VERSE -> ReaderSemanticElement.POEM;
            case POEM_AUTHOR -> ReaderSemanticElement.POEM_AUTHOR;
            case TEXT_AUTHOR -> ReaderSemanticElement.TEXT_AUTHOR;
            case ANNOTATION -> ReaderSemanticElement.ANNOTATION;
            case LINK -> ReaderSemanticElement.LINK;
            case NOTE, FOOTNOTE -> ReaderSemanticElement.FOOTNOTE;
            case BOLD, BOLD_ITALIC, STRONG -> ReaderSemanticElement.STRONG;
            case ITALIC, EMPHASIS -> ReaderSemanticElement.EMPHASIS;
            case CODE -> ReaderSemanticElement.CODE;
            default -> ReaderSemanticElement.BODY;
        };
    }

    private static ReaderElementStyle style(double scale, String weight) {
        return new ReaderElementStyle("", null, scale, weight, "", "", null, null);
    }
}
