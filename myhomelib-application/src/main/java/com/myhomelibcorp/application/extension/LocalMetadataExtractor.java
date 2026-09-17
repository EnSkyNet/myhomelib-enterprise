package com.myhomelibcorp.application.extension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Runtime local-file metadata extractor. It never mutates the catalogue. */
public interface LocalMetadataExtractor {
    String id();
    boolean supports(Path source);
    Map<String, String> extract(Path source) throws IOException;
}
