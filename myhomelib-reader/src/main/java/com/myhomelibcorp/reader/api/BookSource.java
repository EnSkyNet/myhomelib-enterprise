package com.myhomelibcorp.reader.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalLong;

/**
 * Абстракція джерела книги.
 */
public interface BookSource {

    InputStream openStream() throws IOException;

    OptionalLong size();

    String name();

    String extension();

    String id();

    default boolean exists() {
        try {
            return size().isPresent() && size().getAsLong() > 0;
        } catch (Exception e) {
            return false;
        }
    }

}