package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.bookmark.Bookmark;

import java.util.List;
import java.util.Optional;

public interface BookmarkRepository {

    List<Bookmark> findByBookId(String bookId);

    Optional<Bookmark> findById(String id);

    Bookmark save(Bookmark bookmark);

    void deleteById(String id);

    void deleteByBookId(String bookId);

    int countByBookId(String bookId);
}