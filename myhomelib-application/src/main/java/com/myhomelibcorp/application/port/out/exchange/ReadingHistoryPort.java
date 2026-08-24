package com.myhomelibcorp.application.port.out.exchange;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence boundary for the user-visible reading history.
 *
 * <p>The history is deliberately separate from reading progress: clearing it must not
 * remove the resume position, bookmarks, ratings or the Already Read state.</p>
 */
public interface ReadingHistoryPort {

    List<Entry> recent(int limit);

    long count();

    void recordOpened(BookId bookId);

    void clear();

    record Entry(BookId bookId, LocalDateTime lastOpenedAt) {
        public Entry {
            if (bookId == null) throw new IllegalArgumentException("bookId cannot be null");
            if (lastOpenedAt == null) throw new IllegalArgumentException("lastOpenedAt cannot be null");
        }
    }
}
