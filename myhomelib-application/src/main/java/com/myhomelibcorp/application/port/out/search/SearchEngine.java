package com.myhomelibcorp.application.port.out.search;

import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

public interface SearchEngine {
    void index(BookSnapshot snapshot);
    void indexAll(List<BookSnapshot> snapshots);
    void delete(BookId bookId);
    void rebuildIndex();
    void commit();
    SearchResult search(SearchRequest request);
    int getDocumentCount();
    void clear();
}