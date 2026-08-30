package com.myhomelibcorp.application.catalog.collectioninfo;

/** Explicit policy for source-provided collection.info properties. */
public enum CollectionPropertiesTrustPolicy {
    APPLY_SOURCE_PROPERTIES,
    PRESERVE_LOCAL_PROPERTIES,
    MERGE_SAFE_PROPERTIES
}
