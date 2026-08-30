package com.myhomelibcorp.application.port.out.search;

public interface IndexRebuilder {
    /** Clears the currently published derived index before changing collection context. */
    void clearIndex();
    void rebuildIndex();
    int getIndexedDocumentCount();
}