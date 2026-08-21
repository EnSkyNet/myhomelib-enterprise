package com.myhomelibcorp.reader.api;

import java.util.List;
import java.util.Optional;

public interface BookFormatRegistry {

    Optional<BookFormat> findFormat(BookSource source);

    Optional<BookFormat> findByExtension(String extension);

    Optional<BookFormat> findById(String id);

    List<BookFormat> getAllFormats();

    void register(BookFormat format);

    default boolean isSupported(BookSource source) {
        return findFormat(source).isPresent();
    }
}