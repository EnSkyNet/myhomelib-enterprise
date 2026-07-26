package com.myhomelibcorp.reader.service;

import javafx.scene.web.WebEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ReaderJsBridge {

    public int getParagraphCount(WebEngine engine) {
        try {
            Object result = engine.executeScript("document.querySelectorAll('p[data-paragraph-id]').length");
            return result instanceof Number ? ((Number) result).intValue() : 0;
        } catch (Exception e) {
            log.warn("Не вдалося отримати кількість абзаців", e);
            return 0;
        }
    }

    public int getFirstVisibleParagraphIndex(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                    "(function() {" +
                            "    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');" +
                            "    if (paragraphs.length === 0) return -1;" +
                            "    for (var i = 0; i < paragraphs.length; i++) {" +
                            "        var rect = paragraphs[i].getBoundingClientRect();" +
                            "        if (rect.bottom > 0 && rect.top < window.innerHeight) {" +
                            "            return i;" +
                            "        }" +
                            "    }" +
                            "    return 0;" +
                            "})();"
            );
            return result instanceof Number ? ((Number) result).intValue() : -1;
        } catch (Exception e) {
            log.warn("Не вдалося отримати індекс першого видимого абзацу", e);
            return -1;
        }
    }

    public int getCharOffsetForParagraph(WebEngine engine, int index) {
        try {
            Object result = engine.executeScript(
                    "(function() {" +
                            "    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');" +
                            "    if (paragraphs.length <= " + index + ") return 0;" +
                            "    var el = paragraphs[" + index + "];" +
                            "    var text = el.innerText;" +
                            "    if (text.length === 0) return 0;" +
                            "    var rect = el.getBoundingClientRect();" +
                            "    var viewportTop = 0;" +
                            "    var viewportBottom = window.innerHeight;" +
                            "    var visibleTop = Math.max(rect.top, viewportTop);" +
                            "    var visibleBottom = Math.min(rect.bottom, viewportBottom);" +
                            "    var visibleHeight = Math.max(0, visibleBottom - visibleTop);" +
                            "    var totalHeight = rect.bottom - rect.top;" +
                            "    if (totalHeight <= 0) return 0;" +
                            "    var ratio = visibleHeight / totalHeight;" +
                            "    if (ratio < 0) ratio = 0;" +
                            "    if (ratio > 1) ratio = 1;" +
                            "    return Math.floor(ratio * text.length);" +
                            "})();"
            );
            return result instanceof Number ? ((Number) result).intValue() : 0;
        } catch (Exception e) {
            log.warn("Не вдалося отримати зсув для абзацу {}", index, e);
            return 0;
        }
    }

    public double getScrollPercent(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                    "(function() {" +
                            "    var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;" +
                            "    var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;" +
                            "    if (scrollHeight <= 0) return 0;" +
                            "    return scrollTop / scrollHeight;" +
                            "})();"
            );
            return result instanceof Number ? ((Number) result).doubleValue() : 0.0;
        } catch (Exception e) {
            log.debug("Не вдалося отримати відсоток прокрутки", e);
            return 0.0;
        }
    }

    public double getParagraphPositionPercent(WebEngine engine, int index) {
        try {
            Object result = engine.executeScript(
                    "(function() {" +
                            "    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');" +
                            "    if (paragraphs.length <= " + index + ") return 0;" +
                            "    var el = paragraphs[" + index + "];" +
                            "    var docHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;" +
                            "    if (docHeight <= 0) return 0;" +
                            "    var elTop = el.getBoundingClientRect().top + window.scrollY;" +
                            "    return elTop / docHeight;" +
                            "})();"
            );
            return result instanceof Number ? Math.min(1.0, ((Number) result).doubleValue()) : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public boolean scrollToParagraph(WebEngine engine, int index, int charOffset) {
        try {
            String js =
                    "(function() {" +
                            "    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');" +
                            "    if (paragraphs.length <= INDEX) return false;" +
                            "    var el = paragraphs[INDEX];" +
                            "    if (!el) return false;" +
                            "    var text = el.innerText;" +
                            "    if (text.length === 0) { el.scrollIntoView({ block: 'start' }); return true; }" +
                            "    var offset = OFFSET;" +
                            "    if (offset < 0) offset = 0;" +
                            "    if (offset > text.length) offset = text.length;" +
                            "    var textNode = el.firstChild;" +
                            "    while (textNode && textNode.nodeType !== Node.TEXT_NODE) { textNode = textNode.nextSibling; }" +
                            "    if (textNode) {" +
                            "        var range = document.createRange();" +
                            "        range.setStart(textNode, offset);" +
                            "        range.setEnd(textNode, offset);" +
                            "        var rect = range.getClientRects()[0];" +
                            "        if (rect) {" +
                            "            var targetY = rect.top + window.scrollY - window.innerHeight * 0.05;" +
                            "            window.scrollTo({ top: targetY, behavior: 'auto' });" +
                            "        } else { el.scrollIntoView({ block: 'start' }); }" +
                            "    } else { el.scrollIntoView({ block: 'start' }); }" +
                            "    return true;" +
                            "})();";
            // Заміна плейсхолдерів
            js = js.replace("INDEX", String.valueOf(index))
                    .replace("OFFSET", String.valueOf(charOffset));
            Object result = engine.executeScript(js);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Помилка прокрутки до абзацу {}", index, e);
            return false;
        }
    }

    public String getTextAtPosition(WebEngine engine, double position) {
        try {
            String js = "(function(pos) { var body = document.body.innerText; var len = body.length; var p = Math.floor(pos * len); var start = Math.max(0, p - 100); var end = Math.min(len, p + 100); return body.substring(start, end); })(" + position + ");";
            Object result = engine.executeScript(js);
            return result != null ? result.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public String getCurrentChapterTitle(WebEngine engine) {
        try {
            Object title = engine.executeScript("document.querySelector('.chapter-title')?.innerText || ''");
            return title != null ? title.toString() : "Без заголовка";
        } catch (Exception e) {
            return "Без заголовка";
        }
    }

    public void setupScrollListener(WebEngine engine) {
        try {
            engine.executeScript(
                    "if (typeof window.progress === 'undefined') { window.progress = 0; }" +
                            "window.addEventListener('scroll', function() {" +
                            "  var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;" +
                            "  var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;" +
                            "  var progress = scrollHeight > 0 ? scrollTop / scrollHeight : 0;" +
                            "  window.progress = progress;" +
                            "});"
            );
        } catch (Exception e) {
            log.error("Не вдалося налаштувати слухач прокрутки", e);
        }
    }
}