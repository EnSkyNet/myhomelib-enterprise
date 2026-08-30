package com.myhomelibcorp.application.catalog.collectioninfo;

/** MyHomeLib collection.info properties. Script is preserved verbatim, including new lines. */
public record CollectionSourceProperties(
        String name,
        String fileName,
        int type,
        String notes,
        String url,
        String connectionScript) {
}
