package com.myhomelibcorp.reader.render.pdf;

import com.myhomelibcorp.reader.render.api.ArgbRenderedPage;

/** Immutable ARGB page bitmap produced off the JavaFX thread. */
public record PdfRenderedPage(int pageIndex, int width, int height, int[] argb) implements ArgbRenderedPage {
    public PdfRenderedPage {
        ArgbRenderedPage.validate(pageIndex, width, height, argb);
    }
}
