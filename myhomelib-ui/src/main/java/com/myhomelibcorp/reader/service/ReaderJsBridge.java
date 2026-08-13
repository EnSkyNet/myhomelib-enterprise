package com.myhomelibcorp.reader.service;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class ReaderJsBridge {

    /**
     * Виконує JavaScript на FX потоку з очікуванням результату.
     */
    public Object executeScriptOnFxThread(WebEngine engine, String script) {
        if (engine == null) {
            throw new IllegalStateException("WebEngine is null");
        }

        if (Platform.isFxApplicationThread()) {
            return executeScriptSafe(engine, script);
        }

        // Якщо не на FX потоці - виконуємо через Platform.runLater з очікуванням
        CompletableFuture<Object> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Object result = executeScriptSafe(engine, script);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while executing script", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to execute script", e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Script execution timed out", e);
        }
    }

    public boolean isContentLoaded(WebEngine engine) {
        if (engine == null) {
            return false;
        }
        try {
            Object result = executeScriptOnFxThread(engine,
                    "document.readyState === 'complete' && document.body !== null");
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("JS помилка при виконанні: document.readyState === 'complete' && document.body !== null", e);
            return false;
        }
    }

    public int getParagraphCount(WebEngine engine) {
        if (!isEngineReady(engine)) {
            return 0;
        }
        try {
            Object result = executeScriptOnFxThread(engine,
                    "document.querySelectorAll('p[data-paragraph-id]').length");
            return result instanceof Number ? ((Number) result).intValue() : 0;
        } catch (Exception e) {
            log.warn("Не вдалося отримати кількість абзаців", e);
            return 0;
        }
    }

    public int getFirstVisibleParagraphIndex(WebEngine engine) {
        if (!isEngineReady(engine)) {
            return -1;
        }
        try {
            String script = """
                (function() {
                    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');
                    if (paragraphs.length === 0) return -1;
                    for (var i = 0; i < paragraphs.length; i++) {
                        var rect = paragraphs[i].getBoundingClientRect();
                        if (rect.bottom > 0 && rect.top < window.innerHeight) {
                            return i;
                        }
                    }
                    return 0;
                })();
            """;
            Object result = executeScriptOnFxThread(engine, script);
            return result instanceof Number ? ((Number) result).intValue() : -1;
        } catch (Exception e) {
            log.warn("Не вдалося отримати індекс видимого абзацу", e);
            return -1;
        }
    }

    public int getCharOffsetForParagraph(WebEngine engine, int index) {
        if (!isEngineReady(engine)) {
            return 0;
        }
        try {
            String script = """
                (function() {
                    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');
                    if (paragraphs.length <= INDEX) return 0;
                    var el = paragraphs[INDEX];
                    var text = el.innerText;
                    if (text.length === 0) return 0;
                    var rect = el.getBoundingClientRect();
                    var viewportTop = 0;
                    var viewportBottom = window.innerHeight;
                    var visibleTop = Math.max(rect.top, viewportTop);
                    var visibleBottom = Math.min(rect.bottom, viewportBottom);
                    var visibleHeight = Math.max(0, visibleBottom - visibleTop);
                    var totalHeight = rect.bottom - rect.top;
                    if (totalHeight <= 0) return 0;
                    var ratio = visibleHeight / totalHeight;
                    if (ratio < 0) ratio = 0;
                    if (ratio > 1) ratio = 1;
                    return Math.floor(ratio * text.length);
                })();
            """.replace("INDEX", String.valueOf(index));
            Object result = executeScriptOnFxThread(engine, script);
            return result instanceof Number ? ((Number) result).intValue() : 0;
        } catch (Exception e) {
            log.warn("Не вдалося отримати зсув для абзацу {}", index, e);
            return 0;
        }
    }

    public double getScrollPercent(WebEngine engine) {
        if (!isEngineReady(engine)) {
            return 0.0;
        }
        try {
            String script = """
                (function() {
                    var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;
                    var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                    if (scrollHeight <= 0) return 0;
                    return scrollTop / scrollHeight;
                })();
            """;
            Object result = executeScriptOnFxThread(engine, script);
            return result instanceof Number ? ((Number) result).doubleValue() : 0.0;
        } catch (Exception e) {
            log.debug("Не вдалося отримати відсоток прокрутки", e);
            return 0.0;
        }
    }

    public double getParagraphPositionPercent(WebEngine engine, int index) {
        if (!isEngineReady(engine)) {
            return 0.0;
        }
        try {
            String script = """
                (function() {
                    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');
                    if (paragraphs.length <= INDEX) return 0;
                    var el = paragraphs[INDEX];
                    var docHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                    if (docHeight <= 0) return 0;
                    var elTop = el.getBoundingClientRect().top + window.scrollY;
                    return Math.min(1.0, elTop / docHeight);
                })();
            """.replace("INDEX", String.valueOf(index));
            Object result = executeScriptOnFxThread(engine, script);
            return result instanceof Number ? ((Number) result).doubleValue() : 0.0;
        } catch (Exception e) {
            log.warn("Не вдалося отримати позицію абзацу {}", index, e);
            return 0.0;
        }
    }

    public boolean scrollToParagraph(WebEngine engine, int index, int charOffset) {
        if (!isEngineReady(engine)) {
            return false;
        }
        try {
            String script = """
                (function() {
                    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');
                    if (paragraphs.length <= INDEX) return false;
                    var el = paragraphs[INDEX];
                    if (!el) return false;
                    var text = el.innerText;
                    if (text.length === 0) { el.scrollIntoView({ block: 'start' }); return true; }
                    var offset = OFFSET;
                    if (offset < 0) offset = 0;
                    if (offset > text.length) offset = text.length;
                    var textNode = el.firstChild;
                    while (textNode && textNode.nodeType !== Node.TEXT_NODE) { textNode = textNode.nextSibling; }
                    if (textNode) {
                        var range = document.createRange();
                        range.setStart(textNode, offset);
                        range.setEnd(textNode, offset);
                        var rect = range.getClientRects()[0];
                        if (rect) {
                            var targetY = rect.top + window.scrollY - window.innerHeight * 0.05;
                            window.scrollTo({ top: targetY, behavior: 'auto' });
                        } else { el.scrollIntoView({ block: 'start' }); }
                    } else { el.scrollIntoView({ block: 'start' }); }
                    return true;
                })();
            """.replace("INDEX", String.valueOf(index))
                    .replace("OFFSET", String.valueOf(charOffset));
            Object result = executeScriptOnFxThread(engine, script);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Помилка прокрутки до абзацу {}", index, e);
            return false;
        }
    }

    public String getTextAtPosition(WebEngine engine, double position) {
        if (!isEngineReady(engine)) {
            return "";
        }
        try {
            String script = """
                (function(pos) {
                    var body = document.body.innerText;
                    var len = body.length;
                    var p = Math.floor(pos * len);
                    var start = Math.max(0, p - 100);
                    var end = Math.min(len, p + 100);
                    return body.substring(start, end);
                })(""" + position + ")";
            Object result = executeScriptOnFxThread(engine, script);
            return result != null ? result.toString().trim() : "";
        } catch (Exception e) {
            log.warn("Не вдалося отримати текст на позиції {}", position, e);
            return "";
        }
    }

    public String getCurrentChapterTitle(WebEngine engine) {
        if (!isEngineReady(engine)) {
            return "Без заголовка";
        }
        try {
            Object title = executeScriptOnFxThread(engine,
                    "document.querySelector('.chapter-title')?.innerText || ''");
            return title != null ? title.toString() : "Без заголовка";
        } catch (Exception e) {
            log.warn("Не вдалося отримати назву розділу", e);
            return "Без заголовка";
        }
    }

    public void setupScrollListener(WebEngine engine) {
        if (!isEngineReady(engine)) {
            return;
        }
        try {
            executeScriptOnFxThread(engine, """
                if (typeof window.progress === 'undefined') { window.progress = 0; }
                window.addEventListener('scroll', function() {
                    var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;
                    var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                    var progress = scrollHeight > 0 ? scrollTop / scrollHeight : 0;
                    window.progress = progress;
                });
            """);
        } catch (Exception e) {
            log.error("Не вдалося налаштувати слухач прокрутки", e);
        }
    }

    public void cleanup(WebEngine engine) {
        if (engine == null) {
            return;
        }
        try {
            executeScriptOnFxThread(engine, "window.myhomelib = null; window.progress = null;");
        } catch (Exception e) {
            log.debug("Помилка очищення Bridge: {}", e.getMessage());
        }
    }

    private boolean isEngineReady(WebEngine engine) {
        if (engine == null) {
            return false;
        }
        Worker.State state = engine.getLoadWorker().getState();
        return state == Worker.State.SUCCEEDED || state == Worker.State.READY;
    }

    private Object executeScriptSafe(WebEngine engine, String script) {
        if (engine == null) {
            throw new IllegalStateException("WebEngine is null");
        }
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Not on FX application thread");
        }
        try {
            return engine.executeScript(script);
        } catch (Exception e) {
            log.error("JS помилка при виконанні: {}", script, e);
            throw e;
        }
    }
}