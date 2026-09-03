package com.myhomelibcorp.ui.collection;

/** Runtime state projected from the collection's Operation Center lifecycle. */
public enum CollectionRuntimeState {
    CREATING,
    READY,
    IMPORTING,
    INDEXING,
    UPDATING,
    ERROR,
    DELETING
}
