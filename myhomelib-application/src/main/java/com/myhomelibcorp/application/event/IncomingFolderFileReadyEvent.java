package com.myhomelibcorp.application.event;

import com.myhomelibcorp.shared.event.BaseDomainEvent;

import java.nio.file.Path;

/** Raised only after MHL-113 stability + content-fingerprint checks reach READY. */
public final class IncomingFolderFileReadyEvent extends BaseDomainEvent {
    private final String collectionId;
    private final Path file;
    private final String fingerprint;

    public IncomingFolderFileReadyEvent(String collectionId, Path file, String fingerprint) {
        super("incoming-folder-file-ready");
        this.collectionId = collectionId;
        this.file = file;
        this.fingerprint = fingerprint;
    }

    public String collectionId() { return collectionId; }
    public Path file() { return file; }
    public String fingerprint() { return fingerprint; }
}
