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

    /**
     * Отримує зміст з сесії (збережений під час парсингу).
     */
    public List<Chapter> getToc(ReaderSession session) {
        if (session == null || session.getBook() == null) {
            return new ArrayList<>();
        }
        return session.getChapters() != null ? session.getChapters() : new ArrayList<>();
    }

    /**
     * Переходить до вказаного розділу.
     */
    public boolean navigateToChapter(ReaderSession session, Chapter chapter) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return false;
        }

        if (chapter == null) {
            return false;
        }

        // ВИПРАВЛЕНО: спочатку шукаємо главу за назвою або id
        // якщо не знайдено, використовуємо paragraphId
        String script = String.format("""
        (function() {
            var chapterTitle = '%s';
            var paragraphId = '%s';
            
            // 1. Шукаємо главу за назвою
            var chapters = document.querySelectorAll('.chapter');
            for (var i = 0; i < chapters.length; i++) {
                var titleEl = chapters[i].querySelector('.chapter-title');
                if (titleEl && titleEl.innerText.trim() === chapterTitle) {
                    // Знайшли главу - шукаємо перший параграф у ній
                    var firstP = chapters[i].querySelector('p[data-paragraph-id]');
                    if (firstP) {
                        var id = firstP.getAttribute('data-paragraph-id');
                        var index = parseInt(id.replace('p', '')) - 1;
                        if (index >= 0) {
                            return index;
                        }
                    }
                    // Якщо немає параграфів, скролимо до глави
                    chapters[i].scrollIntoView({ block: 'start' });
                    return -1;
                }
            }
            
            // 2. Якщо не знайшли за назвою, шукаємо за paragraphId
            if (paragraphId) {
                var p = document.querySelector('p[data-paragraph-id="' + paragraphId + '"]');
                if (p) {
                    p.scrollIntoView({ block: 'start' });
                    return -1;
                }
                var index = parseInt(paragraphId.replace('p', '')) - 1;
                if (index >= 0) {
                    return index;
                }
            }
            
            return -1;
        })();
    """,
                chapter.getTitle() != null ? chapter.getTitle().replace("'", "\\'") : "",
                chapter.getParagraphId() != null ? chapter.getParagraphId() : ""
        );

        try {
            Object result = session.getWebEngine().executeScript(script);
            if (result instanceof Number) {
                int index = ((Number) result).intValue();
                if (index >= 0) {
                    // Використовуємо ReaderJsBridge для скролу до параграфа
                    return jsBridge.scrollToParagraph(session.getWebEngine(), index, 0);
                }
                return true; // вже виконали скрол
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to navigate to chapter: {}", chapter.getTitle(), e);
            return false;
        }
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

    /**
     * Отримує назву поточного розділу з позиції.
     * Більше не використовує DOM.
     */
    public String getCurrentChapterTitle(ReaderSession session) {
        if (session == null || !session.isActive()) {
            return "";
        }

        // 1. Спроба отримати з позиції
        ReaderPosition pos = positionService.getCurrentPosition(session);
        if (pos != null && pos.getChapterTitle() != null && !pos.getChapterTitle().isEmpty()) {
            return pos.getChapterTitle();
        }

        // 2. Спроба отримати збережену позицію (якщо ще не завантажена)
        if (session.getRestorePosition() != null) {
            String title = session.getRestorePosition().getChapterTitle();
            if (title != null && !title.isEmpty()) {
                return title;
            }
        }

        // 3. Fallback: якщо немає позиції - перший розділ з TOC
        List<Chapter> chapters = getToc(session);
        if (!chapters.isEmpty()) {
            return chapters.get(0).getTitle();
        }

        // 4. Останній fallback
        return "Розділ 1";
    }

    /**
     * Знаходить розділ за позицією.
     */
    public Chapter findChapterAtPosition(ReaderSession session, ReaderPosition position) {
        if (session == null || position == null) {
            return null;
        }

        List<Chapter> chapters = getToc(session);
        if (chapters.isEmpty()) {
            return null;
        }

        // Шукаємо розділ за paragraphId
        String paragraphId = position.getParagraphId();
        if (paragraphId != null && !paragraphId.isEmpty()) {
            return findChapterByParagraphId(chapters, paragraphId);
        }

        // Якщо не знайдено - повертаємо перший
        return chapters.get(0);
    }

    private Chapter findChapterByParagraphId(List<Chapter> chapters, String paragraphId) {
        for (Chapter chapter : chapters) {
            if (chapter.getParagraphId() != null && chapter.getParagraphId().equals(paragraphId)) {
                return chapter;
            }
            // Рекурсивно шукаємо в дітях
            if (chapter.getChildren() != null && !chapter.getChildren().isEmpty()) {
                Chapter found = findChapterByParagraphId(chapter.getChildren(), paragraphId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Отримує наступний розділ.
     */
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

    /**
     * Отримує попередній розділ.
     */
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