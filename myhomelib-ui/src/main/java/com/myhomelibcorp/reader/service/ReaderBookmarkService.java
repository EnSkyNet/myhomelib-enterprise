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

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderBookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final ReaderPositionService positionService;

    public Bookmark addBookmark(ReaderSession session) {
        if (session == null || session.getBook() == null) {
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
                })(""" + position.getPercent() / 100.0 + ")";

            Object result = session.getWebEngine().executeScript(script);
            return result != null ? result.toString().trim() : "";
        } catch (Exception e) {
            log.debug("Failed to get context text: {}", e.getMessage());
            return "";
        }
    }

    public void removeBookmark(String bookmarkId) {
        if (bookmarkId == null) {
            return;
        }
        bookmarkRepository.deleteById(bookmarkId);
        log.info("Bookmark removed: {}", bookmarkId);
    }

    public List<Bookmark> getBookmarks(String bookId) {
        if (bookId == null) {
            return List.of();
        }
        return bookmarkRepository.findByBookId(bookId);
    }

    public int getBookmarkCount(String bookId) {
        return bookmarkRepository.countByBookId(bookId);
    }

    public void goToBookmark(ReaderSession session, Bookmark bookmark, Runnable onComplete) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        if (bookmark == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        int index = extractParagraphIndex(bookmark.getParagraphId());
        int offset = bookmark.getCharOffset();

        positionService.restorePosition(session, ReaderPosition.builder()
                .bookId(bookmark.getBookId())
                .paragraphId(bookmark.getParagraphId())
                .paragraphIndex(index)
                .charOffset(offset)
                .percent(bookmark.getPosition() * 100)
                .build(), () -> {
            log.info("Navigated to bookmark: {}", bookmark.getTitle());
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public boolean goToBookmark(ReaderSession session, Bookmark bookmark) {
        if (session == null || !session.isActive()) {
            return false;
        }

        goToBookmark(session, bookmark, null);
        return true;
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

    public void clearBookmarks(String bookId) {
        if (bookId == null) {
            return;
        }
        bookmarkRepository.deleteByBookId(bookId);
        log.info("All bookmarks cleared for book: {}", bookId);
    }
}