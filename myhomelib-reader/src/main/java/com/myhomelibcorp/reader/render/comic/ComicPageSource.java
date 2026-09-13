package com.myhomelibcorp.reader.render.comic;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** UI/infrastructure-neutral source of image pages for a comic archive. */
public interface ComicPageSource {
    List<String> listPageEntries() throws IOException;
    InputStream openPage(String entryName) throws IOException;
}
