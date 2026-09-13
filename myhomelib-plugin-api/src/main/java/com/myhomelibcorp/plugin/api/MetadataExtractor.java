package com.myhomelibcorp.plugin.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Extracts local-file metadata without importing the book into the catalogue. */
public interface MetadataExtractor {
    String id();
    boolean supports(Path source);
    Map<String, String> extract(Path source, PluginOperationContext context) throws IOException;
}
