package com.myhomelibcorp.epub.optimizer;

/**
 * Controls how optional deep acceptance validators participate in publishing an optimized EPUB.
 * STANDARD rejects only validators that are available and explicitly fail.
 * STRICT additionally requires both EPUBCheck and MyHomeLib reader-reopen validation to be available and pass.
 */
public enum EpubAcceptanceMode {
    STANDARD,
    STRICT
}
