package com.myhomelibcorp.application.catalog.importing;

import java.nio.file.Path;

/** Streaming reader contract for source catalog formats. */
public interface CatalogReader {
    boolean supports(Path source);
    CatalogReadSession open(Path source);
    String formatName();
}
