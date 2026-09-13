package com.myhomelibcorp.reader.render.pdf;

import java.util.Objects;

/** One text-layer search hit with page-local match coordinates and a compact snippet. */
public record PdfSearchResult(int pageIndex, int matchStart, int matchEnd, String snippet) {
    public PdfSearchResult {
        if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must be >= 0");
        if (matchStart < 0) throw new IllegalArgumentException("matchStart must be >= 0");
        if (matchEnd < matchStart) throw new IllegalArgumentException("matchEnd must be >= matchStart");
        snippet = Objects.requireNonNullElse(snippet, "").strip();
    }
}
