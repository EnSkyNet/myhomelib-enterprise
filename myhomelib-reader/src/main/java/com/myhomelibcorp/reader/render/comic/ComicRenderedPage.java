package com.myhomelibcorp.reader.render.comic;

import com.myhomelibcorp.reader.render.api.ArgbRenderedPage;

/** Immutable ARGB page bitmap produced off the JavaFX thread. */
public record ComicRenderedPage(int pageIndex, int width, int height, int[] argb) implements ArgbRenderedPage {
    public ComicRenderedPage {
        ArgbRenderedPage.validate(pageIndex, width, height, argb);
    }
}
