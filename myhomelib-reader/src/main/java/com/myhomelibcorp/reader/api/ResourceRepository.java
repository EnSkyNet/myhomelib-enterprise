package com.myhomelibcorp.reader.api;

import java.io.InputStream;
import java.util.Optional;

public interface ResourceRepository {

    Optional<ResourceInfo> getInfo(String id);

    Optional<InputStream> open(String id);

    Iterable<String> getAllIds();

    int count();

    long totalSize();

    default boolean exists(String id) {
        return getInfo(id).isPresent();
    }
}