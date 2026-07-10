package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

public interface SearchCache {
    List<BookId> get(String query);
    void put(String query, List<BookId> ids);
    void evict(String query);
    void clear();
}