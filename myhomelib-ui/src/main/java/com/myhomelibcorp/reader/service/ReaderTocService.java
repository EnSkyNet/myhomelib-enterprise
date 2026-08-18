package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ReaderPosition;
import com.myhomelibcorp.reader.session.ReaderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderTocService {

    private final ReaderJsBridge jsBridge;
    private final ReaderPositionService positionService;

    public List<Chapter> getToc(ReaderSession session) {
        if (session == null || session.getBook() == null) {
            return new ArrayList<>();
        }
        return session.getChapters() != null ? session.getChapters() : new ArrayList<>();
    }

    /**
     * ВИПРАВЛЕНО: використовує Chapter.paragraphId безпосередньо
     */
    public boolean navigateToChapter(ReaderSession session, Chapter chapter) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return false;
        }

        if (chapter == null) {
            return false;
        }

        // Використовуємо paragraphId з chapter
        String paragraphId = chapter.getParagraphId();
        if (paragraphId != null && !paragraphId.isEmpty()) {
            int index = extractParagraphIndex(paragraphId);
            if (index >= 0) {
                log.info("Navigating to chapter {} via paragraphId: {}", chapter.getTitle(), paragraphId);
                return jsBridge.scrollToParagraph(session.getWebEngine(), index, 0);
            }
        }

        // Fallback: якщо немає paragraphId, пробуємо знайти за назвою
        log.warn("No paragraphId for chapter: {}, trying fallback", chapter.getTitle());
        return navigateByTitleFallback(session, chapter);
    }

    /**
     * Fallback метод для навігації за назвою (залишено для зворотної сумісності)
     */
    private boolean navigateByTitleFallback(ReaderSession session, Chapter chapter) {
        String title = chapter.getTitle();
        if (title == null || title.isEmpty()) {
            return false;
        }

        try {
            String script = String.format("""
                (function() {
                    var title = '%s';
                    var chapters = document.querySelectorAll('.chapter');
                    for (var i = 0; i < chapters.length; i++) {
                        var titleEl = chapters[i].querySelector('.chapter-title');
                        if (titleEl && titleEl.innerText.trim() === title) {
                            var firstP = chapters[i].querySelector('p[data-paragraph-id]');
                            if (firstP) {
                                var id = firstP.getAttribute('data-paragraph-id');
                                var index = parseInt(id.replace('p', '')) - 1;
                                if (index >= 0) {
                                    return index;
                                }
                            }
                            chapters[i].scrollIntoView({ block: 'start' });
                            return -1;
                        }
                    }
                    return -1;
                })();
            """, title.replace("'", "\\'"));

            Object result = session.getWebEngine().executeScript(script);
            if (result instanceof Number) {
                int index = ((Number) result).intValue();
                if (index >= 0) {
                    return jsBridge.scrollToParagraph(session.getWebEngine(), index, 0);
                }
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to navigate to chapter by title: {}", title, e);
        }
        return false;
    }

    private int extractParagraphIndex(String paragraphId) {
        if (paragraphId == null) return -1;
        try {
            if (paragraphId.startsWith("p")) {
                return Integer.parseInt(paragraphId.substring(1)) - 1;
            }
            return Integer.parseInt(paragraphId) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String getCurrentChapterTitle(ReaderSession session) {
        if (session == null || !session.isActive()) {
            return "";
        }

        ReaderPosition pos = positionService.getCurrentPosition(session);
        if (pos != null && pos.getChapterTitle() != null && !pos.getChapterTitle().isEmpty()) {
            return pos.getChapterTitle();
        }

        if (session.getRestorePosition() != null) {
            String title = session.getRestorePosition().getChapterTitle();
            if (title != null && !title.isEmpty()) {
                return title;
            }
        }

        List<Chapter> chapters = getToc(session);
        if (!chapters.isEmpty()) {
            return chapters.get(0).getTitle();
        }

        return "Розділ 1";
    }

    public Chapter findChapterAtPosition(ReaderSession session, ReaderPosition position) {
        if (session == null || position == null) {
            return null;
        }

        List<Chapter> chapters = getToc(session);
        if (chapters.isEmpty()) {
            return null;
        }

        String paragraphId = position.getParagraphId();
        if (paragraphId != null && !paragraphId.isEmpty()) {
            return findChapterByParagraphId(chapters, paragraphId);
        }

        return chapters.get(0);
    }

    private Chapter findChapterByParagraphId(List<Chapter> chapters, String paragraphId) {
        for (Chapter chapter : chapters) {
            if (chapter.getParagraphId() != null && chapter.getParagraphId().equals(paragraphId)) {
                return chapter;
            }
            if (chapter.getChildren() != null && !chapter.getChildren().isEmpty()) {
                Chapter found = findChapterByParagraphId(chapter.getChildren(), paragraphId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public Chapter getNextChapter(ReaderSession session) {
        List<Chapter> chapters = getToc(session);
        if (chapters.isEmpty()) {
            return null;
        }

        String currentTitle = getCurrentChapterTitle(session);
        if (currentTitle.isEmpty()) {
            return chapters.get(0);
        }

        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getTitle().equals(currentTitle)) {
                if (i + 1 < chapters.size()) {
                    return chapters.get(i + 1);
                }
                return null;
            }
        }
        return null;
    }

    public Chapter getPreviousChapter(ReaderSession session) {
        List<Chapter> chapters = getToc(session);
        if (chapters.isEmpty()) {
            return null;
        }

        String currentTitle = getCurrentChapterTitle(session);
        if (currentTitle.isEmpty()) {
            return chapters.get(0);
        }

        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getTitle().equals(currentTitle)) {
                if (i > 0) {
                    return chapters.get(i - 1);
                }
                return null;
            }
        }
        return null;
    }
}