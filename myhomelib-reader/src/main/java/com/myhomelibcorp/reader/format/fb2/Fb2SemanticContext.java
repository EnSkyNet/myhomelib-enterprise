package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.TextStyle;

/**
 * Small state holder for FB2 semantic containers. Keeping this state outside the
 * streaming parser prevents the XML event loop from accumulating presentation rules.
 */
final class Fb2SemanticContext {
    private int poemDepth;
    private int epigraphDepth;
    private int citeDepth;
    private int annotationDepth;
    private boolean footnoteBody;

    boolean beginBody(String bodyName, ParseOptions options) {
        footnoteBody = isFootnoteBody(bodyName);
        return !footnoteBody || options.loadFootnotes();
    }

    /**
     * FB2 permits arbitrary names on the primary body (some generators put the book
     * title there). Only well-known auxiliary note-body names should be treated as
     * footnotes. Treating every non-blank name as notes made inspection with
     * loadFootnotes=false discard perfectly valid main text.
     */
    private static boolean isFootnoteBody(String bodyName) {
        if (bodyName == null || bodyName.isBlank()) return false;
        String normalized = bodyName.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("notes")
                || normalized.equals("note")
                || normalized.equals("footnotes")
                || normalized.equals("footnote")
                || normalized.equals("comments")
                || normalized.equals("comment")
                || normalized.equals("endnotes")
                || normalized.equals("endnote");
    }

    void endBody() {
        poemDepth = 0;
        epigraphDepth = 0;
        citeDepth = 0;
        annotationDepth = 0;
        footnoteBody = false;
    }

    /** @return true when the element is a semantic container consumed here. */
    boolean enter(String element) {
        return switch (element) {
            case "poem" -> { poemDepth++; yield true; }
            case "epigraph" -> { epigraphDepth++; yield true; }
            case "cite" -> { citeDepth++; yield true; }
            case "annotation" -> { annotationDepth++; yield true; }
            default -> false;
        };
    }

    void exit(String element) {
        switch (element) {
            case "poem" -> poemDepth = decrement(poemDepth);
            case "epigraph" -> epigraphDepth = decrement(epigraphDepth);
            case "cite" -> citeDepth = decrement(citeDepth);
            case "annotation" -> annotationDepth = decrement(annotationDepth);
            default -> { }
        }
    }

    TextStyle paragraphStyle(String tag, boolean inTitle, int sectionDepth) {
        return Fb2ParseSupport.styleForParagraph(tag, inTitle, sectionDepth,
                poemDepth > 0, epigraphDepth > 0, citeDepth > 0, annotationDepth > 0, footnoteBody);
    }

    private static int decrement(int value) {
        return value > 0 ? value - 1 : 0;
    }
}
