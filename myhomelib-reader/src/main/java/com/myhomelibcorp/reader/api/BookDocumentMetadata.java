package com.myhomelibcorp.reader.api;

import java.util.List;
import java.util.Optional;

public interface BookDocumentMetadata {

    String id();

    String title();

    List<String> authors();

    String language();

    Optional<String> series();

    Optional<Integer> sequenceNumber();

    List<String> genres();

    String annotation();

    String publisher();

    String year();

    String isbn();

    long fileSize();

    long estimatedCharacterCount();

    boolean hasImages();

    int chapterCount();
}