package com.myhomelibcorp.application.port.out.search;

public interface IndexRebuilder {
    void rebuildIndex();
    int getIndexedDocumentCount();
}