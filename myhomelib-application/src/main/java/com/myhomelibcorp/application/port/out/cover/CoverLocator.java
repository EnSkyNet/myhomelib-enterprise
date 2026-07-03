package com.myhomelibcorp.application.port.out.cover;

import com.myhomelibcorp.application.dto.BookDto;

import java.nio.file.Path;
import java.util.Optional;

public interface CoverLocator {
    Optional<Path> locateCoverFile(BookDto book);
    Optional<String> locateCoverInArchive(BookDto book, Path archivePath);
}