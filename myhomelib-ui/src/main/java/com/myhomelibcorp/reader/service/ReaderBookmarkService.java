package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.session.ReaderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Сервіс для роботи з закладками Reader.
 * Використовує асинхронний API для навігації.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderBookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final ReaderPositionService positionService;

    /**
     * Додає закладку на поточній позиції.
     * @param session сесія Reader
     * @return створена закладка або null
     */
    public Bookmark addBookmark(ReaderSession session) {
        if (session == null || session.getBook() == null) {
            log.warn("Cannot add bookmark: session or book is null");
            return null;
        }

        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            log.warn("No active collection, cannot save bookmark");
            return null;
        }

        ReaderPosition position = positionService.getCurrentPosition(session);
        if (position == null) {
            log.warn("Cannot get current position for bookmark");
            return null;
        }

        String bookId = session.getBookId();
        String context = getContextText(session, position);
        String chapterTitle = position.getChapterTitle();

        Bookmark bookmark = Bookmark.builder()
                .id(UUID.randomUUID().toString())
                .bookId(bookId)
                .paragraphId(position.getParagraphId())
                .charOffset(position.getCharOffset())
                .position(position.getPercent() / 100.0)
                .chapterTitle(chapterTitle != null && !chapterTitle.isEmpty() ? chapterTitle : "Зміст")
                .context(context)
                .createdAt(LocalDateTime.now())
                .build();

        bookmarkRepository.save(bookmark);
        log.info("Bookmark added for book {}: {}", bookId, bookmark.getTitle());

        return bookmark;
    }

    /**
     * Видаляє закладку за ID.
     * @param bookmarkId ID закладки
     */
    public void removeBookmark(String bookmarkId) {
        if (bookmarkId == null) {
            return;
        }
        bookmarkRepository.deleteById(bookmarkId);
        log.info("Bookmark removed: {}", bookmarkId);
    }

    /**
     * Отримує всі закладки для книги.
     * @param bookId ID книги
     * @return список закладок
     */
    public List<Bookmark> getBookmarks(String bookId) {
        if (bookId == null) {
            return List.of();
        }
        return bookmarkRepository.findByBookId(bookId);
    }

    /**
     * Отримує кількість закладок для книги.
     * @param bookId ID книги
     * @return кількість закладок
     */
    public int getBookmarkCount(String bookId) {
        if (bookId == null) {
            return 0;
        }
        return bookmarkRepository.countByBookId(bookId);
    }

    /**
     * АСИНХРОННИЙ API: перехід до закладки.
     * @param session сесія Reader
     * @param bookmark закладка
     * @param onComplete callback після завершення навігації
     */
    public void goToBookmark(ReaderSession session, Bookmark bookmark, Runnable onComplete) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            log.warn("Cannot go to bookmark: session is not active");
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        if (bookmark == null) {
            log.warn("Cannot go to bookmark: bookmark is null");
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        ReaderPosition position = ReaderPosition.builder()
                .bookId(bookmark.getBookId())
                .paragraphId(bookmark.getParagraphId())
                .paragraphIndex(extractParagraphIndex(bookmark.getParagraphId()))
                .charOffset(bookmark.getCharOffset())
                .percent(bookmark.getPosition() * 100)
                .chapterTitle(bookmark.getChapterTitle())
                .build();

        log.info("Navigating to bookmark: {} (paragraph: {}, charOffset: {})",
                bookmark.getTitle(), bookmark.getParagraphId(), bookmark.getCharOffset());

        positionService.restorePosition(session, position, () -> {
            log.info("Successfully navigated to bookmark: {}", bookmark.getTitle());
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    /**
     * Синхронна обгортка для асинхронного API.
     * @deprecated Використовуйте {@link #goToBookmark(ReaderSession, Bookmark, Runnable)}
     */
    @Deprecated
    public boolean goToBookmark(ReaderSession session, Bookmark bookmark) {
        if (session == null || !session.isActive()) {
            return false;
        }

        goToBookmark(session, bookmark, null);
        return true;
    }

    /**
     * Отримує текстовий контекст навколо позиції для закладки.
     */
    private String getContextText(ReaderSession session, ReaderPosition position) {
        if (session.getWebEngine() == null) {
            return "";
        }

        try {
            String script = """
                (function(pos) {
                    var body = document.body.innerText || '';
                    var len = body.length;
                    if (len === 0) return '';
                    var p = Math.floor(pos * len);
                    var start = Math.max(0, p - 80);
                    var end = Math.min(len, p + 80);
                    var text = body.substring(start, end);
                    if (start > 0) text = '...' + text;
                    if (end < len) text = text + '...';
                    return text.replace(/\\n/g, ' ').replace(/\\s+/g, ' ');
                })(%f)
            """.formatted(position.getPercent() / 100.0);

            Object result = session.getWebEngine().executeScript(script);
            return result != null ? result.toString().trim() : "";
        } catch (Exception e) {
            log.debug("Failed to get context text: {}", e.getMessage());
            return "";
        }
    }

    /**
     * ВИПРАВЛЕНО: видаляє всі закладки для книги з перевіркою на null.
     * @param bookId ID книги (якщо null або порожній — метод нічого не робить)
     */
    public void clearBookmarks(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            log.debug("clearBookmarks called with null or empty bookId, skipping");
            return;
        }
        bookmarkRepository.deleteByBookId(bookId);
        log.info("All bookmarks cleared for book: {}", bookId);
    }

    /**
     * Отримує закладку за ID.
     * @param bookmarkId ID закладки
     * @return закладка або null
     */
    public Bookmark getBookmark(String bookmarkId) {
        if (bookmarkId == null) {
            return null;
        }
        return bookmarkRepository.findById(bookmarkId).orElse(null);
    }

    /**
     * Перевіряє, чи існує закладка для книги.
     * @param bookId ID книги
     * @return true якщо є закладки
     */
    public boolean hasBookmarks(String bookId) {
        return getBookmarkCount(bookId) > 0;
    }

    private int extractParagraphIndex(String paragraphId) {
        if (paragraphId == null) return 0;
        try {
            if (paragraphId.startsWith("p")) {
                return Integer.parseInt(paragraphId.substring(1));
            }
            return Integer.parseInt(paragraphId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}