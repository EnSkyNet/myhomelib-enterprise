package com.myhomelibcorp.application.port.out.contentindexing;

import com.myhomelibcorp.application.content.indexing.ContentIndexQueueCheckpoint;

import java.util.Optional;

public interface ContentIndexQueueCheckpointPort {
    Optional<ContentIndexQueueCheckpoint> load(String collectionId);
    void save(ContentIndexQueueCheckpoint checkpoint);
    void clear(String collectionId);
}
