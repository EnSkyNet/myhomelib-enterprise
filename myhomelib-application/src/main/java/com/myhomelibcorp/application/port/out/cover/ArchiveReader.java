package com.myhomelibcorp.application.port.out.cover;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public interface ArchiveReader {
    boolean isArchive(Path file);
    List<String> listEntries(Path archivePath);
    Optional<InputStream> readEntry(Path archivePath, String entryName);
    Optional<InputStream> findFirstEntry(Path archivePath, Predicate<String> filter);
}