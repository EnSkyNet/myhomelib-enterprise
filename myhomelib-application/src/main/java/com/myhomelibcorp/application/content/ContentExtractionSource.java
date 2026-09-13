package com.myhomelibcorp.application.content;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalLong;

/** Stream-oriented source for format-neutral full-text extraction. */
public interface ContentExtractionSource {
    String id();
    String name();
    InputStream openStream() throws IOException;
    OptionalLong size();
}
