package com.myhomelibcorp.application.event;

import com.myhomelibcorp.shared.event.BaseDomainEvent;

import java.nio.file.Path;

/** Raised only when a configured local collection source obtains a new fingerprint. */
public final class CollectionSourceUpdateAvailableEvent extends BaseDomainEvent {
    private final String collectionId;
    private final Path sourceFile;
    private final String fingerprint;

    public CollectionSourceUpdateAvailableEvent(String collectionId, Path sourceFile, String fingerprint) {
        super("collection-source-update-available");
        this.collectionId = collectionId;
        this.sourceFile = sourceFile;
        this.fingerprint = fingerprint;
    }

    public String collectionId() { return collectionId; }
    public Path sourceFile() { return sourceFile; }
    public String fingerprint() { return fingerprint; }
}
