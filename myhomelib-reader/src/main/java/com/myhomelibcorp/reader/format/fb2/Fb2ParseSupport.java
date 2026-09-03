package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Shared low-level FB2 token/text utilities used by the streaming parser. */
final class Fb2ParseSupport {
    private Fb2ParseSupport() {}

    static String safeElementText(XMLStreamReader reader) throws XMLStreamException {
        String value = reader.getElementText();
        return value != null ? value : "";
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static boolean isParagraphTag(String tag) {
        return switch (tag) {
            case "p", "subtitle", "v", "text-author" -> true;
            default -> false;
        };
    }

    static TextStyle styleForParagraph(
            String tag, boolean inTitle, int sectionDepth,
            boolean inPoem, boolean inEpigraph, boolean inCite, boolean inAnnotation,
            boolean inFootnoteBody) {
        if (inTitle) {
            return sectionDepth <= 1 ? TextStyle.CHAPTER_TITLE : TextStyle.SECTION_TITLE;
        }
        return switch (tag) {
            case "subtitle" -> TextStyle.SUBTITLE;
            case "v" -> TextStyle.VERSE;
            case "text-author" -> inPoem ? TextStyle.POEM_AUTHOR : TextStyle.TEXT_AUTHOR;
            case "p" -> {
                if (inFootnoteBody) yield TextStyle.FOOTNOTE;
                if (inAnnotation) yield TextStyle.ANNOTATION;
                if (inEpigraph) yield TextStyle.EPIGRAPH;
                if (inCite) yield TextStyle.CITE;
                if (inPoem) yield TextStyle.POEM;
                yield TextStyle.NORMAL;
            }
            default -> TextStyle.NORMAL;
        };
    }

    static TextStyle inlineStyleFor(String tag) {
        return switch (tag) {
            case "strong" -> TextStyle.STRONG;
            case "b" -> TextStyle.BOLD;
            case "emphasis" -> TextStyle.EMPHASIS;
            case "i" -> TextStyle.ITALIC;
            case "a" -> TextStyle.LINK;
            case "code" -> TextStyle.CODE;
            case "sup" -> TextStyle.SUPERSCRIPT;
            case "sub" -> TextStyle.SUBSCRIPT;
            case "strikethrough", "s" -> TextStyle.STRIKETHROUGH;
            default -> null;
        };
    }

    static TextStyle combineInlineStyles(TextStyle current, TextStyle incoming) {
        TextStyle a = current != null ? current : TextStyle.NORMAL;
        TextStyle b = incoming != null ? incoming : TextStyle.NORMAL;
        if (a == TextStyle.NORMAL) return b;
        if (b == TextStyle.NORMAL || a == b) return a;

        boolean aBold = a == TextStyle.BOLD || a == TextStyle.STRONG || a == TextStyle.BOLD_ITALIC;
        boolean aItalic = a == TextStyle.ITALIC || a == TextStyle.EMPHASIS || a == TextStyle.BOLD_ITALIC;
        boolean bBold = b == TextStyle.BOLD || b == TextStyle.STRONG || b == TextStyle.BOLD_ITALIC;
        boolean bItalic = b == TextStyle.ITALIC || b == TextStyle.EMPHASIS || b == TextStyle.BOLD_ITALIC;
        if ((aBold && bItalic) || (aItalic && bBold)) return TextStyle.BOLD_ITALIC;
        return b;
    }

    /** Returns whether the last emitted character is whitespace. */
    static boolean appendNormalized(TextStorageImpl storage, String raw, TextStyle style, boolean lastWasSpace) {
        if (raw == null || raw.isEmpty()) return lastWasSpace;
        StringBuilder out = new StringBuilder(raw.length());
        boolean space = lastWasSpace;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!space) {
                    out.append(' ');
                    space = true;
                }
            } else {
                out.append(c);
                space = false;
            }
        }
        if (!out.isEmpty()) storage.append(out.toString(), style);
        return space;
    }

    static void appendPlainNormalized(StringBuilder target, String raw) {
        if (raw == null || raw.isEmpty()) return;
        boolean lastSpace = !target.isEmpty() && Character.isWhitespace(target.charAt(target.length() - 1));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastSpace && !target.isEmpty()) {
                    target.append(' ');
                    lastSpace = true;
                }
            } else {
                target.append(c);
                lastSpace = false;
            }
        }
    }

    static String buildAuthor(String first, String middle, String last, String nick) {
        StringBuilder result = new StringBuilder();
        appendPart(result, first);
        appendPart(result, middle);
        appendPart(result, last);
        if (result.isEmpty()) appendPart(result, nick);
        return result.toString().trim();
    }

    private static void appendPart(StringBuilder result, String value) {
        if (value == null || value.isBlank()) return;
        if (!result.isEmpty()) result.append(' ');
        result.append(value.trim());
    }

    static String cleanTitle(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    static void closeQuietly(Writer writer) {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }

    static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
