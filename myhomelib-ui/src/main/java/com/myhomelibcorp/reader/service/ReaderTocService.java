package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.session.ReaderSession;
import javafx.scene.web.WebEngine;
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

    public List<Chapter> getToc(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return new ArrayList<>();
        }

        WebEngine engine = session.getWebEngine();

        if (!jsBridge.isContentLoaded(engine)) {
            return new ArrayList<>();
        }

        try {
            String script = """
                (function() {
                    var chapters = [];
                    var chapterElements = document.querySelectorAll('.chapter');
                    for (var i = 0; i < chapterElements.length; i++) {
                        var el = chapterElements[i];
                        var titleEl = el.querySelector('.chapter-title');
                        var title = titleEl ? titleEl.innerText.trim() : 'Розділ ' + (i + 1);
                        var firstParagraph = el.querySelector('p[data-paragraph-id]');
                        var paragraphId = firstParagraph ? firstParagraph.getAttribute('data-paragraph-id') : '';
                        var level = 1;
                        var parent = el.parentElement;
                        while (parent) {
                            if (parent.classList && parent.classList.contains('chapter')) {
                                level++;
                            }
                            parent = parent.parentElement;
                        }
                        chapters.push({
                            id: el.id || 'chapter-' + i,
                            title: title,
                            level: Math.min(level, 6),
                            paragraphId: paragraphId
                        });
                    }
                    return JSON.stringify(chapters);
                })();
            """;

            Object result = engine.executeScript(script);
            if (result == null) {
                return new ArrayList<>();
            }

            return parseChapters(result.toString());

        } catch (Exception e) {
            log.warn("Failed to get TOC: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Chapter> parseChapters(String json) {
        List<Chapter> chapters = new ArrayList<>();
        try {
            String[] items = json.split("\\}\\s*,\\s*\\{");
            for (String item : items) {
                String clean = item.replaceAll("[{}\"]", "");
                String id = extractField(clean, "id");
                String title = extractField(clean, "title");
                String paragraphId = extractField(clean, "paragraphId");
                int level = extractIntField(clean, "level");

                Chapter chapter = Chapter.builder()
                        .id(id)
                        .title(title)
                        .level(level)
                        .paragraphId(paragraphId)
                        .build();
                chapters.add(chapter);
            }
        } catch (Exception e) {
            log.warn("Failed to parse chapters: {}", json, e);
        }
        return chapters;
    }

    private String extractField(String text, String key) {
        String prefix = key + ":";
        int start = text.indexOf(prefix);
        if (start == -1) return "";
        start += prefix.length();
        int end = text.indexOf(",", start);
        if (end == -1) end = text.length();
        String value = text.substring(start, end).trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private int extractIntField(String text, String key) {
        try {
            return Integer.parseInt(extractField(text, key));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public boolean navigateToChapter(ReaderSession session, Chapter chapter) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return false;
        }

        if (chapter == null || chapter.getParagraphId() == null || chapter.getParagraphId().isEmpty()) {
            return false;
        }

        int index = extractParagraphIndex(chapter.getParagraphId());
        return jsBridge.scrollToParagraph(session.getWebEngine(), index, 0);
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

    public String getCurrentChapterTitle(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return "";
        }
        return jsBridge.getCurrentChapterTitle(session.getWebEngine());
    }
}