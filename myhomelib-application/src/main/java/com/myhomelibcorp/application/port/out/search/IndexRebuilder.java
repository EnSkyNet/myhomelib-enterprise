package com.myhomelibcorp.application.port.out.search;

import java.util.concurrent.atomic.AtomicBoolean;

public interface IndexRebuilder {
    /** Clears the currently published derived index before changing collection context. */
    void clearIndex();
    void rebuildIndex();

    /** Cancellation-aware rebuild. Implementations should preserve the previous committed index when cancelled. */
    default void rebuildIndex(AtomicBoolean cancelFlag) { rebuildIndex(); }

    int getIndexedDocumentCount();
}
