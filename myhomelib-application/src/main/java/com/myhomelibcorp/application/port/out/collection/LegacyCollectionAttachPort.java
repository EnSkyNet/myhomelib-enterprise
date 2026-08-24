package com.myhomelibcorp.application.port.out.collection;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.nio.file.Path;

public interface LegacyCollectionAttachPort {
    AttachResult attach(Path databaseFile, String collectionName, Path collectionRoot);

    record AttachResult(Collection collection, long books, long authors, long genres, boolean migratedLegacy) { }
}
