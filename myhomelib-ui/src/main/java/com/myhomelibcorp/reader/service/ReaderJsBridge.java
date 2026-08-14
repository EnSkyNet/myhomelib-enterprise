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
            log.error("JS error checking content loaded", e);
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
            log.warn("Failed to get paragraph count", e);
            return 0;
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
            log.error("Failed to scroll to paragraph {}", index, e);
            return false;
        }
    }

    public String getCurrentChapterTitle(WebEngine engine) {
        if (!isEngineReady(engine)) {
            return "";
        }
        try {
            // Шукаємо найближчий .chapter-title до видимої області
            String script = """
                (function() {
                    var paragraphs = document.querySelectorAll('p[data-paragraph-id]');
                    if (paragraphs.length === 0) return '';
                    var firstVisible = 0;
                    for (var i = 0; i < paragraphs.length; i++) {
                        var rect = paragraphs[i].getBoundingClientRect();
                        if (rect.bottom > 0 && rect.top < window.innerHeight) {
                            firstVisible = i;
                            break;
                        }
                    }
                    var el = paragraphs[firstVisible];
                    var chapterEl = el.closest('.chapter');
                    if (chapterEl) {
                        var titleEl = chapterEl.querySelector('.chapter-title');
                        if (titleEl) {
                            return titleEl.innerText || '';
                        }
                    }
                    return '';
                })();
            """;
            Object result = executeScriptOnFxThread(engine, script);
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log.warn("Failed to get current chapter title", e);
            return "";
        }
    }

    public void cleanup(WebEngine engine) {
        if (engine == null) {
            return;
        }
        try {
            executeScriptOnFxThread(engine, "window.myhomelib = null;");
        } catch (Exception e) {
            log.debug("Cleanup error: {}", e.getMessage());
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
            log.error("JS error: {}", script, e);
            throw e;
        }
    }
}