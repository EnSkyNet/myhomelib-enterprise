package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.bookmark.Bookmark;

import java.util.List;

public interface BookmarkRepository {

    List<Bookmark> findByBookId(String bookId);

    Bookmark save(Bookmark bookmark);

    void deleteById(String id);

    int countByBookId(String bookId);
}