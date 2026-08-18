package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderSettings;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.model.ReaderReadingStats;
import com.myhomelibcorp.reader.session.ReaderSession;
import com.myhomelibcorp.reader.session.ReaderSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final ReaderStatsService statsService;
    private final ReaderScheduler scheduler;

    private final AtomicBoolean isClosing = new AtomicBoolean(false);
    private final AtomicBoolean isRestoring = new AtomicBoolean(false);

    // ==================== ВІДКРИТТЯ ТА ЗАКРИТТЯ КНИГИ ====================

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
                            bookId, (int) pos.getPercent(), pos.getParagraphId());
                });

        statsService.loadOrCreateStats(bookId.asString(), book.getTitle());

        return session;
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
        savePositionNow(session);
        statsService.endReadingSession(session);
        sessionManager.closeCurrentSession();

        log.info("Book closed");
        isClosing.set(false);
    }

    public boolean isBookOpen() {
        return sessionManager.hasActiveSession();
    }

    public boolean isClosing() {
        return isClosing.get();
    }

    public BookDto getCurrentBook() {
        ReaderSession session = sessionManager.getCurrentSession();
        return session != null ? session.getBook() : null;
    }

    public ReaderSession getCurrentSession() {
        return sessionManager.getCurrentSession();
    }

    // ==================== ЗАВАНТАЖЕННЯ КОНТЕНТУ ====================

    public void loadBookContent(ReaderSession session, Runnable onLoaded) {
        loadBookContent(session, onLoaded, null);
    }

    public void loadBookContent(ReaderSession session, Runnable onLoaded, Consumer<String> progressConsumer) {
        if (session == null || session.getBook() == null) {
            if (onLoaded != null) {
                scheduler.runOnFxThread(onLoaded);
            }
            return;
        }

        statsService.startReadingSession(session);

        if (progressConsumer != null) {
            scheduler.runOnFxThread(() -> progressConsumer.accept("Читання файлу..."));
        }

        contentService.loadBookContent(session, () -> {
            restorePositionAfterLoad(session, null);
            if (onLoaded != null) {
                scheduler.runOnFxThread(onLoaded);
            }
        });
    }

    // ==================== ПОЗИЦІЯ ====================

    public void saveCurrentPosition() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return;
        }
        positionService.savePositionNow(session);
    }

    public void schedulePositionSave(ReaderSession session) {
        if (session == null || !session.isActive()) {
            return;
        }
        positionService.scheduleSave(session);
    }

    public void savePositionNow(ReaderSession session) {
        if (session == null || !session.isActive()) {
            return;
        }
        positionService.savePositionNow(session);
    }

    public void restorePositionAfterLoad(ReaderSession session, Runnable onComplete) {
        if (session == null || !session.isActive()) {
            if (onComplete != null) {
                scheduler.runOnFxThread(onComplete);
            }
            return;
        }

        if (isRestoring.getAndSet(true)) {
            log.debug("Position restore already in progress, skipping duplicate");
            if (onComplete != null) {
                scheduler.runOnFxThread(onComplete);
            }
            return;
        }

        try {
            ReaderPosition pos = session.getRestorePosition();
            if (pos == null || pos.getParagraphId() == null || pos.getParagraphId().isEmpty()) {
                updateProgressUI(session, 0);
                isRestoring.set(false);
                if (onComplete != null) {
                    scheduler.runOnFxThread(onComplete);
                }
                return;
            }

            log.info("Restoring position: book={}, paragraph={}, percent={}%",
                    session.getBookId(), pos.getParagraphId(), (int)pos.getPercent());

            positionService.restorePosition(session, pos, () -> {
                updateProgressUI(session, pos.getPercent());
                log.info("Position restored for book {}: {}%, paragraph {}",
                        session.getBookId(), (int) pos.getPercent(), pos.getParagraphId());
                isRestoring.set(false);
                if (onComplete != null) {
                    scheduler.runOnFxThread(onComplete);
                }
            });
        } catch (Exception e) {
            isRestoring.set(false);
            log.warn("Failed to restore position: {}", e.getMessage());
            if (onComplete != null) {
                scheduler.runOnFxThread(onComplete);
            }
        }
    }

    @Deprecated
    public void restorePositionAfterLoad(ReaderSession session) {
        restorePositionAfterLoad(session, null);
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

    public ReaderPosition getCurrentPosition() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return null;
        }
        return positionService.getCurrentPosition(session);
    }

    public void updateProgressFromCurrentPosition() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return;
        }
        ReaderPosition pos = positionService.getCurrentPosition(session);
        if (pos != null) {
            updateProgressUI(session, pos.getPercent());
            statsService.updateProgress(session);
        }
    }

    private void updateProgressUI(ReaderSession session, double percent) {
        if (session == null) {
            return;
        }
        session.setProgressPercent(percent);
    }

    // ==================== ЗАКЛАДКИ ====================

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

    public void goToBookmark(Bookmark bookmark) {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            return;
        }
        bookmarkService.goToBookmark(session, bookmark, null);
    }

    public void goToBookmark(Bookmark bookmark, Runnable onComplete) {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || !session.isActive()) {
            if (onComplete != null) {
                scheduler.runOnFxThread(onComplete);
            }
            return;
        }
        bookmarkService.goToBookmark(session, bookmark, onComplete);
    }

    public int getBookmarkCount() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || session.getBook() == null) {
            return 0;
        }
        return bookmarkService.getBookmarkCount(session.getBookId());
    }

    public boolean hasBookmarks() {
        return getBookmarkCount() > 0;
    }

    // ==================== ЗМІСТ (TOC) ====================

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

    // ==================== НАЛАШТУВАННЯ ====================

    public ReaderSettings getSettings() {
        return settingsService.getSettings();
    }

    public void saveSettings() {
        settingsService.save();
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

    public void resetSettings() {
        settingsService.resetToDefaults();
        ReaderSession session = sessionManager.getCurrentSession();
        if (session != null && session.isActive()) {
            applySettings(session);
        }
    }

    public List<String> getAvailableFonts() {
        return settingsService.getAvailableFonts();
    }

    // ==================== ZOOM ====================

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

    // ==================== КЕШ ====================

    public void clearCache() {
        contentService.clearCache();
        positionService.clearCache();
        statsService.clearCache();
        log.info("Reader cache cleared");
    }

    public void clearImageCache() {
        contentService.clearImageCache();
        log.info("Image cache cleared");
    }

    // ==================== СТАТИСТИКА ====================

    public ReaderReadingStats getReadingStats() {
        ReaderSession session = sessionManager.getCurrentSession();
        if (session == null || session.getBook() == null) {
            return null;
        }
        return statsService.getStats(session.getBookId());
    }

    // ==================== СЕРВІСИ ДОСТУПУ ====================

    public ReaderPositionService getPositionService() {
        return positionService;
    }

    public ReaderBookmarkService getBookmarkService() {
        return bookmarkService;
    }

    public ReaderTocService getTocService() {
        return tocService;
    }

    public ReaderContentService getContentService() {
        return contentService;
    }

    public ReaderStatsService getStatsService() {
        return statsService;
    }
}