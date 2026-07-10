package com.myhomelibcorp.application.port.out.storage;

import java.nio.file.Path;
import java.util.Optional;

public interface CoverStorage {
    void save(String bookId, byte[] imageData, String mimeType);
    Optional<byte[]> load(String bookId);
    Optional<Path> getCoverPath(String bookId);
    void delete(String bookId);
    void deleteAll();
    boolean exists(String bookId);
    long getTotalSize();
}