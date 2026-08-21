// myhomelib-reader/src/main/java/com/myhomelibcorp/reader/service/ReaderBookmarkManager.java
package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.model.ReaderBookmark;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class ReaderBookmarkManager {

    private final ConcurrentMap<String, List<ReaderBookmark>> bookmarksByBook = new ConcurrentHashMap<>();

    public ReaderBookmark addBookmark(String bookId, ReaderPosition position, String title, String note) {
        ReaderBookmark bookmark = ReaderBookmark.builder()
                .id(UUID.randomUUID().toString())
                .bookId(bookId)
                .position(position)
                .title(title != null ? title : "Закладка " + (position.chapterIndex() + 1))
                .note(note)
                .build();

        bookmarksByBook.computeIfAbsent(bookId, k -> new ArrayList<>()).add(bookmark);
        log.info("⭐ Додано закладку: {} для книги {}", bookmark.getTitle(), bookId);
        return bookmark;
    }

    public void removeBookmark(String bookmarkId) {
        for (List<ReaderBookmark> list : bookmarksByBook.values()) {
            if (list.removeIf(b -> b.getId().equals(bookmarkId))) {
                log.info("🗑️ Видалено закладку: {}", bookmarkId);
                return;
            }
        }
    }

    public List<ReaderBookmark> getBookmarks(String bookId) {
        return bookmarksByBook.getOrDefault(bookId, new ArrayList<>());
    }

    public boolean hasBookmarks(String bookId) {
        return bookmarksByBook.containsKey(bookId) && !bookmarksByBook.get(bookId).isEmpty();
    }

    public void clearBookmarks(String bookId) {
        bookmarksByBook.remove(bookId);
        log.info("🧹 Очищено закладки для книги {}", bookId);
    }

    public int getBookmarkCount(String bookId) {
        List<ReaderBookmark> list = bookmarksByBook.get(bookId);
        return list != null ? list.size() : 0;
    }
}