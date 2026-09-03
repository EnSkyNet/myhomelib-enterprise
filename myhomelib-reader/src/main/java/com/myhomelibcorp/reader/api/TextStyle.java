package com.myhomelibcorp.reader.api;

public enum TextStyle {
    NORMAL,
    BOLD,
    ITALIC,
    BOLD_ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    SUBSCRIPT,
    SUPERSCRIPT,
    CODE,
    LINK,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    HEADING_4,
    HEADING_5,
    HEADING_6,
    BOOK_TITLE,
    CHAPTER_TITLE,
    SECTION_TITLE,
    SUBTITLE,
    QUOTE,
    EPIGRAPH,
    CITE,
    POEM,
    VERSE,
    POEM_AUTHOR,
    TEXT_AUTHOR,
    NOTE,
    FOOTNOTE,
    ANNOTATION,
    EMPHASIS,
    STRONG;

    public boolean isHeading() {
        return this == HEADING_1 || this == HEADING_2 || this == HEADING_3 ||
                this == HEADING_4 || this == HEADING_5 || this == HEADING_6 ||
                this == BOOK_TITLE || this == CHAPTER_TITLE || this == SECTION_TITLE;
    }

    public int getHeadingLevel() {
        return switch (this) {
            case HEADING_1 -> 1;
            case HEADING_2 -> 2;
            case HEADING_3 -> 3;
            case HEADING_4 -> 4;
            case HEADING_5 -> 5;
            case HEADING_6 -> 6;
            default -> 0;
        };
    }

    public boolean isInline() {
        return this == BOLD || this == ITALIC || this == BOLD_ITALIC ||
                this == UNDERLINE || this == STRIKETHROUGH ||
                this == SUBSCRIPT || this == SUPERSCRIPT ||
                this == CODE || this == LINK ||
                this == EMPHASIS || this == STRONG;
    }
}