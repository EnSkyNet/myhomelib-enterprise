package com.myhomelibcorp.application.port.out.catalog;

import com.myhomelibcorp.application.catalog.collectioninfo.CollectionSourceProperties;

import java.nio.file.Path;
import java.util.Optional;

public interface CollectionInfoPort {
    Optional<CollectionSourceProperties> read(Path inpxFile);
}
