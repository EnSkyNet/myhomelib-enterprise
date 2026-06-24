package com.myhomelibcorp.application.port.out;

public interface IndexRebuilder {
    void rebuildIndex();
    int getIndexedDocumentCount();
}