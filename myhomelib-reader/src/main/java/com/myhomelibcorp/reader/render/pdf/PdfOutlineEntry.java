package com.myhomelibcorp.reader.render.pdf;

import java.util.Objects;

/** Renderer-neutral PDF outline item. Page indexes are zero-based. */
public record PdfOutlineEntry(String title, int pageIndex, int level) {
    public PdfOutlineEntry {
        title = Objects.requireNonNullElse(title, "").strip();
        if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must be >= 0");
        if (level < 0) throw new IllegalArgumentException("level must be >= 0");
    }
}
