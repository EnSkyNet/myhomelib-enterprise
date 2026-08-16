package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderFacade {

    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final SessionService sessionService;
    private final ReaderSessionManager sessionManager;
    private final ReaderContentService contentService;
    private final ReaderPositionService positionService;
    private final ReaderBookmarkService bookmarkService;
    private final ReaderTocService tocService;
    private final ReaderSettingsService settingsService;
    private final ReaderScheduler scheduler;

    private final AtomicBoolean isClosing = new AtomicBoolean(false);

    public ReaderSession openBook(BookId bookId) {
        if (bookId == null) {
            log.warn("Cannot open book: bookId is null");
            return null;
        }

        closeBook();
        isClosing.set(false);

        Optional<BookDto> bookOpt = loadBookByIdUseCase.execute(bookId);
        if (bookOpt.isEmpty()) {
            log.warn("Book not found: {}", bookId);
            return null;
        }

        BookDto book = bookOpt.get();
        sessionService.saveLastOpenedBookId(bookId.asString());

        ReaderSession session = sessionManager.createSession(book);

        log.info("Opening book: {} (session: {})", book.getTitle(), session.getSessionId());

        positionService.loadPosition(bookId.asString())
                .ifPresent(pos -> {
                    session.setRestorePosition(pos);
                    log.debug("Loaded position for book {}: {}%, paragraph {}",
                            bookId, (int)pos.getPercent(), pos.getParagraphIndex());
                });

        return session;
    }

    public void loadBookContent(ReaderSession session, Runnable onLoaded) {
        if (session == null || session.getBook() == null) {
            if (onLoaded != null) {
                scheduler.runOnFxThread(onLoaded);
            }
            return;
        }

        contentService.loadBookContent(session, onLoaded);
    }

    public void restorePositionAfterLoad(ReaderSession session) {
        if (session == null || !session.isActive()) {
            return;
        }

        ReaderPosition pos = session.getRestorePosition();
        if (pos == null) {
            updateProgressUI(session, 0);
            return;
        }

        positionService.restorePosition(session, pos, () -> {
            updateProgressUI(session, pos.getPercent());
            log.info("Position restored for book {}: {}%, paragraph {}",
                    session.getBookId(), (int)pos.getPercent(), pos.getParagraphIndex());
        });
    }

    public void closeBook() {
        if (isClosing.get()) {
            return;
        }

        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return;
        }

        isClosing.set(true);

        positionService.stopPeriodicSaving(session);
        positionService.savePositionNow(session);
        sessionManager.closeCurrentSession();

        log.info("Book closed");
        isClosing.set(false);
    }

    public void saveCurrentPosition() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return;
        }
        positionService.savePositionNow(session);
    }

    public void restorePosition(Runnable onComplete) {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            if (onComplete != null) {
                scheduler.runOnFxThread(onComplete);
            }
            return;
        }

        ReaderPosition pos = session.getRestorePosition();
        if (pos == null) {
            updateProgressUI(session, 0);
            if (onComplete != null) {
                scheduler.runOnFxThread(onComplete);
            }
            return;
        }

        updateProgressUI(session, pos.getPercent());

        positionService.restorePosition(session, pos, () -> {
            updateProgressUI(session, pos.getPercent());
            if (onComplete != null) {
                scheduler.runOnFxThread(onComplete);
            }
        });
    }

    public void updateProgressFromCurrentPosition() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return;
        }
        ReaderPosition pos = positionService.getCurrentPosition(session);
        if (pos != null) {
            updateProgressUI(session, pos.getPercent());
        }
    }

    private void updateProgressUI(ReaderSession session, double percent) {
        if (session == null) {
            return;
        }
        session.setProgressPercent(percent);
    }

    public void setZoom(double zoom) {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null) {
            return;
        }
        session.setZoom(zoom);
    }

    public double getZoom() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null) {
            return 1.0;
        }
        return session.getZoom();
    }

    public void startPeriodicSaving(ReaderSession session) {
        positionService.startPeriodicSaving(session);
    }

    public void stopPeriodicSaving(ReaderSession session) {
        positionService.stopPeriodicSaving(session);
    }

    public List<Chapter> getToc() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return List.of();
        }
        return tocService.getToc(session);
    }

    public boolean navigateToChapter(Chapter chapter) {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return false;
        }
        return tocService.navigateToChapter(session, chapter);
    }

    public String getCurrentChapterTitle() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return "";
        }
        return tocService.getCurrentChapterTitle(session);
    }

    public Bookmark addBookmark() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return null;
        }
        return bookmarkService.addBookmark(session);
    }

    public void removeBookmark(String bookmarkId) {
        bookmarkService.removeBookmark(bookmarkId);
    }

    public List<Bookmark> getBookmarks() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || session.getBook() == null) {
            return List.of();
        }
        return bookmarkService.getBookmarks(session.getBookId());
    }

    public boolean goToBookmark(Bookmark bookmark) {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return false;
        }
        return bookmarkService.goToBookmark(session, bookmark);
    }

    public int getBookmarkCount() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || session.getBook() == null) {
            return 0;
        }
        return bookmarkService.getBookmarkCount(session.getBookId());
    }

    public void toggleTheme() {
        String theme = settingsService.toggleTheme();
        ReaderSession session = sessionManager.getCurrentSession();
        if (session != null && session.isActive()) {
            applySettings(session);
        }
        log.info("Theme toggled to: {}", theme);
    }

    public void setFontSize(double size) {
        settingsService.setFontSize(size);
        ReaderSession session = sessionManager.getCurrentSession();
        if (session != null && session.isActive()) {
            applySettings(session);
        }
    }

    public void setFontFamily(String family) {
        settingsService.setFontFamily(family);
        ReaderSession session = sessionManager.getCurrentSession();
        if (session != null && session.isActive()) {
            applySettings(session);
        }
    }

    public void applySettings(ReaderSession session) {
        if (session != null && session.isActive()) {
            contentService.applySettings(session);
        }
    }

    public ReaderSettings getSettings() {
        return settingsService.getSettings();
    }

    public List<String> getAvailableFonts() {
        return settingsService.getAvailableFonts();
    }

    public void resetSettings() {
        settingsService.resetToDefaults();
        ReaderSession session = sessionManager.getCurrentSession();
        if (session != null && session.isActive()) {
            applySettings(session);
        }
    }

    public boolean isBookOpen() {
        return sessionManager.hasActiveSession();
    }

    public BookDto getCurrentBook() {
        ReaderSession session = sessionManager.getCurrentSession();
        return session != null ? session.getBook() : null;
    }

    public ReaderPosition getCurrentPosition() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return null;
        }
        return positionService.getCurrentPosition(session);
    }

    public boolean isClosing() {
        return isClosing.get();
    }

    public void clearCache() {
        contentService.clearCache();
        positionService.clearCache();
    }

    public void saveSettings() {
        settingsService.save();
    }
}