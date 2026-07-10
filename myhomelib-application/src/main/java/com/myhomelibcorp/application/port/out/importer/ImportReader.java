package com.myhomelibcorp.application.port.out.importer;

import java.nio.file.Path;
import java.util.stream.Stream;

public interface ImportReader {
    boolean supports(Path file);
    Stream<Object[]> read(Path file);
    String getFormatName();
    default long countBooks(Path file) { return -1; }
}