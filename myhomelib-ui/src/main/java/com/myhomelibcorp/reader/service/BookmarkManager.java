package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BookmarkManager {

    private final Map<String, List<Bookmark>> bookmarks = new ConcurrentHashMap<>();

    public void addBookmark(String bookId, Bookmark bookmark) {
        bookmarks.computeIfAbsent(bookId, k -> new ArrayList<>()).add(bookmark);
        log.info("Додано закладку для книги {}: {}", bookId, bookmark.getTitle());
    }

    public void removeBookmark(String bookId, String bookmarkId) {
        List<Bookmark> list = bookmarks.get(bookId);
        if (list != null) {
            list.removeIf(b -> b.getId().equals(bookmarkId));
            if (list.isEmpty()) {
                bookmarks.remove(bookId);
            }
        }
    }

    public List<Bookmark> getBookmarks(String bookId) {
        return bookmarks.getOrDefault(bookId, Collections.emptyList());
    }

    public Optional<Bookmark> getBookmark(String bookId, String bookmarkId) {
        List<Bookmark> list = bookmarks.get(bookId);
        if (list != null) {
            return list.stream().filter(b -> b.getId().equals(bookmarkId)).findFirst();
        }
        return Optional.empty();
    }

    public void clearBookmarks(String bookId) {
        bookmarks.remove(bookId);
    }

    public int getBookmarkCount(String bookId) {
        return bookmarks.getOrDefault(bookId, Collections.emptyList()).size();
    }
}